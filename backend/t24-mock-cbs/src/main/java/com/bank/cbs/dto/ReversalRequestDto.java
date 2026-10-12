package com.bank.cbs.dto;

public record ReversalRequestDto(
        String originalTransactionId,
        String makerId,
        String reason,
        String notes
) {}
