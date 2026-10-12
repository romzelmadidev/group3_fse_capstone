package com.bank.orchestrator.dto;

public record RiskScoreResponse(
        String transactionId,
        int score, // 0 to 100
        String decision, // ALLOW, ADVISORY_WARNING, BLOCK
        String riskReason
) {}
