package com.bank.cbs.controller;

import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.entity.master.TransactionMaster;
import com.bank.cbs.service.CbsBalanceEnquiryService;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.dto.AccountTransactionDto;
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
    private final CbsBalanceEnquiryService balanceEnquiryService;

    public CbsPostingController(CbsFundsTransferService transferService,
                                CbsReversalService reversalService,
                                CbsBalanceEnquiryService balanceEnquiryService) {
        this.transferService = transferService;
        this.reversalService = reversalService;
        this.balanceEnquiryService = balanceEnquiryService;
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
                    var dto = balanceEnquiryService.getBalanceByAccountId(accountId);
                    return ResponseEntity.ok(OfsMessageUtil.buildBalanceEnquiryResponse(
                            dto.accountId(), dto.accountNumber(), dto.currentBalance(), dto.availableBalance(), dto.holdBalance(), dto.currency()
                    ));
                }
            }

            if ("TRANSACTION.LIST".equalsIgnoreCase(operation)
                    || "ENQUIRY.SELECT".equalsIgnoreCase(operation)
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
