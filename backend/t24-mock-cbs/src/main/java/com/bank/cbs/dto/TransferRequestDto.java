package com.bank.cbs.dto;

import java.math.BigDecimal;

public record TransferRequestDto(
        String transactionId,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        String channel,
        String idempotencyKey,
        Boolean fundsHeld,
        String transactionType,
        String orderingCustomer,
        String beneficiaryCustomer,
        String beneficiaryAccount,
        String counterpartyBank,
        String chargeBearer
) {
    public TransferRequestDto(
            String transactionId,
            String sourceAccountId,
            String destinationAccountId,
            BigDecimal amount,
            String currency,
            String description,
            String channel,
            String idempotencyKey,
            Boolean fundsHeld
    ) {
        this(transactionId, sourceAccountId, destinationAccountId, amount, currency, description, channel, idempotencyKey, fundsHeld,
                null, null, null, null, null, "SHA");
    }

    public TransferRequestDto(
            String transactionId,
            String sourceAccountId,
            String destinationAccountId,
            BigDecimal amount,
            String currency,
            String description,
            String channel,
            String idempotencyKey
    ) {
        this(transactionId, sourceAccountId, destinationAccountId, amount, currency, description, channel, idempotencyKey, false);
    }
}
