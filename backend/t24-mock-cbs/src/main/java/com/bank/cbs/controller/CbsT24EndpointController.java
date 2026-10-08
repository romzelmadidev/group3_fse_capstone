package com.bank.cbs.controller;

import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.dto.T24FundsTransferResponse;
import com.bank.ledger.contracts.dto.T24ReversalRequest;
import com.bank.ledger.contracts.dto.T24ReversalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Dual-Endpoint Temenos T24 Core Banking Subsystem (CBS) Controller.
 * Implements the architecture specification:
 * - Endpoint 1: Transfer (POST /t24/funds-transfer or POST /funds-transfer)
 * - Endpoint 2: Reversal (POST /t24/reversal or POST /reversal)
 */
@RestController
@CrossOrigin(origins = "*")
public class CbsT24EndpointController {

    private static final Logger log = LoggerFactory.getLogger(CbsT24EndpointController.class);

    private final CbsFundsTransferService transferService;
    private final CbsReversalService reversalService;

    public CbsT24EndpointController(
            CbsFundsTransferService transferService,
            CbsReversalService reversalService) {
        this.transferService = transferService;
        this.reversalService = reversalService;
    }

    /**
     * Endpoint 1: Transfer
     * Dispatches OFS mutation to T24 Accounting Kernel (Double-Entry Ledger).
     * Supports both T24 standard snake_case and camelCase payloads.
     */
    @PostMapping(value = {
            "/t24/funds-transfer",
            "/funds-transfer",
            "/api/v1/cbs/t24/funds-transfer",
            "/api/v1/cbs/funds-transfer"
    })
    public ResponseEntity<T24FundsTransferResponse> fundsTransfer(@RequestBody Map<String, Object> payload) {
        log.info("Received T24 funds-transfer request: {}", payload);

        String txId = extractString(payload, "transaction_reference", "transactionReference", "transaction_id", "transactionId", "refNumber");
        if (txId == null || txId.isBlank()) {
            txId = "FT" + System.currentTimeMillis();
        }

        String debitAcct = extractString(payload, "debit_account_id", "debitAccountId", "source_account_id", "sourceAccountId", "from_account_id", "fromAccountId", "sourceAccount");
        String creditAcct = extractString(payload, "credit_account_id", "creditAccountId", "target_account_id", "targetAccountId", "destination_account_id", "destinationAccountId", "to_account_id", "toAccountId", "targetAccount");
        BigDecimal amount = extractAmount(payload, "amount");
        String currency = extractString(payload, "currency");
        if (currency == null || currency.isBlank()) {
            currency = "PHP";
        }
        String description = extractString(payload, "description", "memo", "payment_details");
        if (description == null || description.isBlank()) {
            description = "T24 Funds Transfer";
        }
        String idempotencyKey = extractString(payload, "idempotency_key", "idempotencyKey");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = txId;
        }

        if (debitAcct == null || debitAcct.isBlank() || creditAcct == null || creditAcct.isBlank()) {
            return ResponseEntity.badRequest().body(T24FundsTransferResponse.builder()
                    .t24Reference(txId)
                    .status("FAILED")
                    .debitAmount(amount)
                    .currency(currency)
                    .debitAccountId(debitAcct)
                    .creditAccountId(creditAcct)
                    .message("Both debit (source) and credit (target) accounts are required.")
                    .timestamp(Instant.now())
                    .build());
        }

        TransferRequestDto reqDto = new TransferRequestDto(
                txId,
                debitAcct,
                creditAcct,
                amount,
                currency,
                description,
                "T24_API",
                idempotencyKey
        );

        TransferResponseDto coreResp = transferService.executeTransfer(reqDto);

        String ofsResp = String.format(
                "FUNDS.TRANSFER//1,SUCCESS,TRANSACTION.ID:1:1=%s,DEBIT.ACCT:1:1=%s,CREDIT.ACCT:1:1=%s,AMOUNT:1:1=%s",
                coreResp.transactionId(), coreResp.sourceAccountId(), coreResp.destinationAccountId(), coreResp.amount()
        );

        T24FundsTransferResponse response = T24FundsTransferResponse.builder()
                .t24Reference(coreResp.transactionId())
                .status("COMMITTED")
                .debitAccountId(coreResp.sourceAccountId())
                .debitAmount(coreResp.amount())
                .debitBalanceAfter(coreResp.sourceNewBalance())
                .creditAccountId(coreResp.destinationAccountId())
                .creditAmount(coreResp.amount())
                .creditBalanceAfter(coreResp.destinationNewBalance())
                .currency(coreResp.currency())
                .ofsResponse(ofsResp)
                .timestamp(coreResp.postedAt())
                .message("Funds transfer committed successfully to T24 core")
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint 2: Reversal
     * Dispatches OFS reverse to T24 Accounting Kernel (Compensating Double-Entry Ledger).
     * Compensates on failure or automated Saga rollback.
     */
    @PostMapping(value = {
            "/t24/reversal",
            "/reversal",
            "/api/v1/cbs/t24/reversal",
            "/api/v1/cbs/reversal"
    })
    public ResponseEntity<T24ReversalResponse> reversal(@RequestBody Map<String, Object> payload) {
        log.info("Received T24 reversal request: {}", payload);

        String origTxId = extractString(payload, "original_transaction_id", "originalTransactionId", "transaction_id", "transactionId");
        String reason = extractString(payload, "reversal_reason", "reversalReason", "reason");
        if (reason == null || reason.isBlank()) {
            reason = "SAGA_COMPENSATION";
        }
        String checkerId = extractString(payload, "checker_id", "checkerId", "approved_by");
        String makerId = extractString(payload, "maker_id", "makerId", "user_id");

        T24ReversalRequest req = T24ReversalRequest.builder()
                .originalTransactionId(origTxId)
                .reversalReason(reason)
                .checkerId(checkerId)
                .makerId(makerId)
                .build();

        T24ReversalResponse resp = reversalService.executeCompensatingReversal(req);
        return ResponseEntity.ok(resp);
    }

    private String extractString(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object val = map.get(k);
            if (val != null) {
                return val.toString();
            }
        }
        return null;
    }

    private BigDecimal extractAmount(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) {
            return BigDecimal.ZERO;
        }
        if (val instanceof BigDecimal bd) {
            return bd;
        }
        if (val instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue());
        }
        return new BigDecimal(val.toString());
    }
}
