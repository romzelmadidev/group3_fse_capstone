package com.bank.cbs.dto;

import java.time.LocalDate;
import java.util.List;

public record CobExecutionResponseDto(
        LocalDate businessDate,
        String status,
        boolean postingWindowOpen,
        List<CobPhaseResultDto> phaseResults
) {
    public record CobPhaseResultDto(
            int phase,
            String phaseName,
            String status,
            String details
    ) {}
}
