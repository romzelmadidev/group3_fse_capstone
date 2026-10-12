package com.bank.cbs.dto;

public record ReversalActionDto(
        String reversalRequestId,
        String checkerId,
        String rejectionReason,
        String checkerNotes
) {}
