package com.bank.cbs.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CobExecutionResponseDto(
        LocalDate businessDate,
        String status,
        boolean postingWindowOpen,
        List<CobPhaseResultDto> phaseResults
) {
    public String batchId() {
        return "COB-" + (businessDate != null ? businessDate.toString() : "BATCH");
    }

    public int accountsProcessed() {
        return phaseResults != null ? phaseResults.size() : 0;
    }

    public BigDecimal totalFeesCollected() {
        return BigDecimal.ZERO;
    }

    public BigDecimal totalInterestAccrued() {
        return BigDecimal.ZERO;
    }

    public record CobPhaseResultDto(
            int phase,
            String phaseName,
            String status,
            String details
    ) {}
}
