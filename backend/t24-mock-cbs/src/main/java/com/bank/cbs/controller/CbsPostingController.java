package com.bank.cbs.controller;

import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.entity.master.BalanceMaster;
import com.bank.cbs.entity.master.ReversalRequestMaster;
import com.bank.cbs.entity.master.TransactionMaster;
import com.bank.cbs.repository.master.BalanceMasterRepository;
import com.bank.cbs.service.CbsAmountHoldService;
import com.bank.cbs.service.CbsBalanceEnquiryService;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.dto.AmountHoldReleaseResponseDto;
import com.bank.ledger.contracts.dto.AmountHoldRequestDto;
import com.bank.ledger.contracts.dto.AmountHoldResponseDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cbs")
public class CbsPostingController {

    private final CbsFundsTransferService transferService;
    private final CbsReversalService reversalService;
    private final CbsAmountHoldService holdService;
    private final BalanceMasterRepository balanceRepository;
    private final CbsBalanceEnquiryService balanceEnquiryService;

    @org.springframework.beans.factory.annotation.Autowired
    public CbsPostingController(
            CbsFundsTransferService transferService,
            CbsReversalService reversalService,
            CbsAmountHoldService holdService,
            BalanceMasterRepository balanceRepository,
            CbsBalanceEnquiryService balanceEnquiryService) {
        this.transferService = transferService;
        this.reversalService = reversalService;
        this.holdService = holdService;
        this.balanceRepository = balanceRepository;
        this.balanceEnquiryService = balanceEnquiryService;
    }

    public CbsPostingController(
            CbsFundsTransferService transferService,
            CbsReversalService reversalService,
            CbsBalanceEnquiryService balanceEnquiryService) {
        this(transferService, reversalService, null, null, balanceEnquiryService);
    }

    @PostMapping(value = "/postings/transfer", produces = "text/plain")
    public ResponseEntity<String> postTransfer(@RequestBody String body) {
        try {
            TransferRequestDto req;
            if (body.trim().startsWith("{")) {
                req = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, TransferRequestDto.class);
            } else {
                Map<String, String> fields = OfsMessageUtil.parseOfsFields(body);
                String txId = fields.getOrDefault("TXN.ID", fields.getOrDefault("TRANSACTION.ID", UUID.randomUUID().toString()));
                String debitAcct = fields.getOrDefault("DEBIT.ACCT.NO", fields.get("SOURCE.ACCOUNT.ID"));
                String creditAcct = fields.getOrDefault("CREDIT.ACCT.NO", fields.get("DESTINATION.ACCOUNT.ID"));
                BigDecimal amount = new BigDecimal(fields.get("AMOUNT"));
                String currency = fields.getOrDefault("CURRENCY", "PHP");
                String desc = fields.getOrDefault("DESCRIPTION", "Funds Transfer");
                req = new TransferRequestDto(txId, debitAcct, creditAcct, amount, currency, desc, "ORCHESTRATOR", txId);
            }
            TransferResponseDto response = transferService.executeTransfer(req);
            return ResponseEntity.ok(response.ofsResponse());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "ERROR", e.getMessage()));
        }
    }

    @PostMapping(value = "/ofs", consumes = "text/plain", produces = "text/plain")
    public ResponseEntity<String> executeOfs(@RequestBody String ofsMessage) {
        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            String operation = fields.get("OPERATION");

            if ("FUNDS.TRANSFER,INITIATE".equalsIgnoreCase(operation) ||
                (operation != null && operation.contains("FUNDS.TRANSFER") && ofsMessage != null && ofsMessage.contains("INITIATE"))) {
                String txId = fields.containsKey("TXN.ID") ? fields.get("TXN.ID") : UUID.randomUUID().toString();
                String debitAcct = fields.get("DEBIT.ACCT.NO");
                String creditAcct = fields.get("CREDIT.ACCT.NO");
                BigDecimal amount = new BigDecimal(fields.get("AMOUNT"));
                String currency = fields.getOrDefault("CURRENCY", "PHP");

                TransferRequestDto req = new TransferRequestDto(
                        txId,
                        debitAcct,
                        creditAcct,
                        amount,
                        currency,
                        "OFS Transaction",
                        "OFS_GW",
                        txId
                );

                TransferResponseDto resp = transferService.executeTransfer(req);
                return ResponseEntity.ok(resp.ofsResponse());
            }

            if ("FUNDS.TRANSFER,REVERSAL".equalsIgnoreCase(operation)) {
                String originalFtNo = fields.getOrDefault("ORIGINAL.FT.NO", fields.get("TXN.ID"));
                String ticketId = fields.get("TICKET.ID");
                String checkerId = fields.getOrDefault("CHECKER.ID", "MGR02");

                if (ticketId != null && !ticketId.isBlank() && !ticketId.startsWith("FT") && !ticketId.startsWith("TXN")) {
                    try {
                        ReversalActionDto action = new ReversalActionDto(ticketId, checkerId, null, "Approved via OFS Reversal");
                        ReversalRequestMaster approved = reversalService.approveReversal(action);
                        return ResponseEntity.ok(OfsMessageUtil.buildOfsResponse(true, approved.getReversalTxId(), "REVERSAL_APPROVED_AND_SETTLED"));
                    } catch (Exception e) {
                        // Ticket might not exist yet, fallback to resolving via original transaction ID below
                    }
                }

                // If originalFtNo is provided, request and approve in dual control
                if (originalFtNo != null && !originalFtNo.isBlank()) {
                    ReversalRequestDto reqDto = new ReversalRequestDto(
                            originalFtNo,
                            "TELLER_MAKER",
                            "OFS_DISPUTE",
                            "Reversal initiated via OFS protocol"
                    );
                    ReversalRequestMaster ticket = reversalService.requestReversal(reqDto);
                    ReversalActionDto action = new ReversalActionDto(ticket.getTicketId(), checkerId, null, "Approved via OFS Reversal");
                    ReversalRequestMaster approved = reversalService.approveReversal(action);
                    return ResponseEntity.ok(OfsMessageUtil.buildOfsResponse(true, approved.getReversalTxId(), "REVERSAL_APPROVED_AND_SETTLED"));
                }

                return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "MISSING_ORIGINAL_FT_NO", "ORIGINAL.FT.NO or TICKET.ID is required"));
            }

            if ("AC.LOCKED.EVENTS,INPUT".equalsIgnoreCase(operation)) {
                String accountId = fields.get("ACCOUNT.NUMBER");
                String amountStr = fields.get("LOCKED.AMOUNT");
                if (accountId == null || amountStr == null) {
                    return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "INVALID_FIELDS", "ACCOUNT.NUMBER and LOCKED.AMOUNT required"));
                }
                BigDecimal amount = new BigDecimal(amountStr);
                String reason = fields.getOrDefault("HOLD.REASON", "MAKER_CHECKER_HOLD");
                String extRef = fields.getOrDefault("EXT.REF", fields.get("HEADER.REF"));

                AmountHoldRequestDto holdReq = AmountHoldRequestDto.builder()
                        .accountId(accountId)
                        .holdAmount(amount)
                        .reason(reason)
                        .externalReference(extRef)
                        .build();

                AmountHoldResponseDto holdResp = holdService.createHold(holdReq);
                return ResponseEntity.ok(holdResp.getOfsResponse());
            }

            if ("AC.LOCKED.EVENTS,REVERSE".equalsIgnoreCase(operation)) {
                String holdRef = fields.get("HOLD.REF");
                if (holdRef == null || holdRef.isBlank()) {
                    return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "MISSING_HOLD_REF", "HOLD.REF is required"));
                }
                AmountHoldReleaseResponseDto releaseResp = holdService.releaseHold(holdRef);
                return ResponseEntity.ok(releaseResp.getOfsResponse());
            }

            if ("BALANCE.ENQUIRY".equalsIgnoreCase(operation)
                    || (ofsMessage != null && ofsMessage.contains("ENQUIRY.SELECT") && !ofsMessage.contains("TRANSACTION.LIST"))) {
                String accountId = fields.get("ACCOUNT.NUMBER");
                if (accountId == null || accountId.isBlank()) {
                    accountId = fields.get("ACCOUNT.NUMBER:EQ");
                }
                if (accountId == null || accountId.isBlank()) {
                    accountId = fields.get("ACCOUNT.ID");
                }
                if (accountId != null && !accountId.isBlank()) {
                    try {
                        var dto = balanceEnquiryService.getBalanceByAccountId(accountId);
                        return ResponseEntity.ok(OfsMessageUtil.buildBalanceEnquiryResponse(
                                dto.accountId(), dto.accountNumber(), dto.currentBalance(), dto.availableBalance(), dto.holdBalance(), dto.currency()
                        ));
                    } catch (Exception e) {
                        BalanceMaster bal = balanceRepository.findById(accountId).orElse(null);
                        if (bal != null) {
                            return ResponseEntity.ok(String.format(
                                    "//1,SUCCESS,ACCOUNT.NUMBER:1:1=%s,WORKING.BALANCE:1:1=%s,LOCKED.AMOUNT:1:1=%s,AVAILABLE.BALANCE:1:1=%s",
                                    accountId, bal.getBalanceAmount(),
                                    bal.getHoldAmount() != null ? bal.getHoldAmount() : BigDecimal.ZERO,
                                    bal.getAvailableBalance() != null ? bal.getAvailableBalance() : bal.getBalanceAmount()
                            ));
                        }
                    }
                }
                return ResponseEntity.badRequest().body("//-1,FAILURE,ERROR=ACCOUNT_NOT_FOUND");
            }

            if ("TRANSACTION.LIST".equalsIgnoreCase(operation)
                    || (ofsMessage != null && (ofsMessage.contains("TRANSACTION.LIST") || ofsMessage.startsWith("ENQUIRY.SELECT")))) {
                String accountId = fields.get("ACCOUNT.NUMBER");
                if (accountId == null || accountId.isBlank()) {
                    accountId = fields.get("ACCOUNT.NUMBER:EQ");
                }
                if (accountId == null || accountId.isBlank()) {
                    accountId = fields.get("ACCOUNT.ID");
                }
                int page = 0;
                int size = 20;
                try {
                    String pageStr = fields.get("PAGE");
                    if (pageStr != null && !pageStr.isBlank()) {
                        page = Integer.parseInt(pageStr.trim());
                    }
                    String sizeStr = fields.get("SIZE");
                    if (sizeStr == null || sizeStr.isBlank()) {
                        sizeStr = fields.get("LIMIT");
                    }
                    if (sizeStr != null && !sizeStr.isBlank()) {
                        size = Integer.parseInt(sizeStr.trim());
                    }
                } catch (Exception ignored) {
                }

                if (accountId != null && !accountId.isBlank()) {
                    List<TransactionMaster> txList = balanceEnquiryService.getTransactionsByAccountId(accountId, page, size);
                    List<AccountTransactionDto> dtos = txList.stream()
                            .map(tx -> new AccountTransactionDto(
                                    tx.getTransactionId(),
                                    tx.getSourceAccountId(),
                                    tx.getTargetAccountId(),
                                    tx.getAmount(),
                                    tx.getCurrency(),
                                    tx.getTransactionType(),
                                    tx.getStatus(),
                                    tx.getMemo(),
                                    tx.getCreatedAt()
                            )).toList();
                    return ResponseEntity.ok(OfsMessageUtil.buildTransactionEnquiryResponse(accountId, dtos, page, size));
                }
            }

            return ResponseEntity.badRequest().body("//-1,FAILURE,ERROR=UNSUPPORTED_OPERATION");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "ERROR", e.getMessage()));
        }
    }
}
