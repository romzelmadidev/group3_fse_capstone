package com.bank.cbs.controller;

import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.entity.master.ReversalRequestMaster;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Dedicated Temenos T24 OFS Posting Controller.
 * Exposes single canonical endpoints for:
 * 1. Funds Transfer (POST /api/v1/cbs/funds-transfer)
 * 2. Compensating Saga Reversal (POST /api/v1/cbs/reversal)
 * Strictly consumes and produces Temenos OFS syntax (text/plain).
 */
@RestController
@RequestMapping("/api/v1/cbs")
public class CbsPostingController {

    private static final Logger log = LoggerFactory.getLogger(CbsPostingController.class);

    private final CbsFundsTransferService transferService;
    private final CbsReversalService reversalService;

    public CbsPostingController(
            CbsFundsTransferService transferService,
            CbsReversalService reversalService) {
        this.transferService = transferService;
        this.reversalService = reversalService;
    }

    /**
     * Dedicated Funds Transfer OFS Endpoint.
     * Consumes raw Temenos OFS (FUNDS.TRANSFER,INITIATE...) and returns OFS response.
     * Rejects non-OFS/JSON payloads without fallback.
     */
    @PostMapping(value = "/funds-transfer", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> executeFundsTransfer(@RequestBody String ofsMessage) {
        log.info("Received inbound OFS funds-transfer command: {}", ofsMessage);
        if (ofsMessage == null || ofsMessage.trim().isEmpty() || ofsMessage.trim().startsWith("{")) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "INVALID_FORMAT", "Payload must be plain-text Temenos OFS syntax"));
        }

        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);

            String txId = fields.getOrDefault("TXN.ID", fields.getOrDefault("TRANSACTION.ID", fields.get("HEADER.REF")));
            if (txId == null || txId.isBlank()) {
                txId = "FT" + System.currentTimeMillis();
            }

            String debitAcct = fields.getOrDefault("DEBIT.ACCT.NO", fields.get("SOURCE.ACCOUNT.ID"));
            String creditAcct = fields.getOrDefault("CREDIT.ACCT.NO", fields.get("DESTINATION.ACCOUNT.ID"));
            String amountStr = fields.get("AMOUNT");

            if (debitAcct == null || debitAcct.isBlank() || creditAcct == null || creditAcct.isBlank() || amountStr == null || amountStr.isBlank()) {
                return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(
                        false, "MISSING_REQUIRED_FIELDS", "DEBIT.ACCT.NO, CREDIT.ACCT.NO, and AMOUNT are required in OFS payload"
                ));
            }

            BigDecimal amount = new BigDecimal(amountStr.trim());
            String currency = fields.getOrDefault("CURRENCY", "PHP");
            String desc = fields.getOrDefault("DESCRIPTION", fields.getOrDefault("PAYMENT.DETAILS", "T24 Funds Transfer"));
            String idempotencyKey = fields.getOrDefault("IDEMPOTENCY.KEY", txId);

            TransferRequestDto req = new TransferRequestDto(
                    txId,
                    debitAcct,
                    creditAcct,
                    amount,
                    currency,
                    desc,
                    "T24_OFS_POSTING",
                    idempotencyKey
            );

            TransferResponseDto response = transferService.executeTransfer(req);
            return ResponseEntity.ok(response.ofsResponse());
        } catch (Exception e) {
            log.error("Failed to execute OFS funds transfer: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "ERROR", e.getMessage()));
        }
    }

    /**
     * Dedicated Saga Compensating Reversal OFS Endpoint.
     * Consumes raw Temenos OFS (FUNDS.TRANSFER,REVERSAL...) and returns OFS response.
     * Rejects non-OFS/JSON payloads without fallback.
     */
    @PostMapping(value = "/reversal", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> executeReversal(@RequestBody String ofsMessage) {
        log.info("Received inbound OFS reversal command: {}", ofsMessage);
        if (ofsMessage == null || ofsMessage.trim().isEmpty() || ofsMessage.trim().startsWith("{")) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "INVALID_FORMAT", "Payload must be plain-text Temenos OFS syntax"));
        }

        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            String originalFtNo = fields.getOrDefault("ORIGINAL.FT.NO", fields.getOrDefault("TXN.ID", fields.get("HEADER.REF")));

            if (originalFtNo == null || originalFtNo.isBlank()) {
                return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(
                        false, "MISSING_ORIGINAL_FT_NO", "ORIGINAL.FT.NO is required in OFS payload"
                ));
            }

            String reason = fields.getOrDefault("REASON", fields.getOrDefault("REVERSAL.REASON", "SAGA_COMPENSATION"));
            String checkerId = fields.getOrDefault("CHECKER.ID", fields.getOrDefault("CHECKER", "SYSTEM_SAGA"));
            String makerId = fields.getOrDefault("MAKER.ID", fields.getOrDefault("MAKER", "SAGA_COORDINATOR"));

            ReversalRequestDto reqDto = new ReversalRequestDto(
                    originalFtNo,
                    makerId,
                    reason,
                    "Automated compensating saga reversal via OFS"
            );
            ReversalRequestMaster ticket = reversalService.requestReversal(reqDto);
            ReversalActionDto action = new ReversalActionDto(ticket.getTicketId(), checkerId, null, "Approved via OFS Reversal");
            ReversalRequestMaster approved = reversalService.approveReversal(action);

            return ResponseEntity.ok(OfsMessageUtil.buildOfsResponse(true, approved.getReversalTxId(), "REVERSAL_APPROVED_AND_SETTLED"));
        } catch (Exception e) {
            log.error("Failed to execute OFS reversal: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "ERROR", e.getMessage()));
        }
    }
}
