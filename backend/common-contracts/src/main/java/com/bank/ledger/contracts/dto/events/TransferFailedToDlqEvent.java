package com.bank.ledger.contracts.dto.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransferFailedToDlqEvent {
    private String incidentId;
    private String correlationId;
    private String transactionId;
    private String errorType;
    private String errorCode;
    private String circuitBreakerState;
    private String sourceAccountId;
    private String destinationAccountId;
    private BigDecimal amount;
    private String currency;
    private Instant failureTimestampUtc;
    private String stackTrace;
    private Map<String, Object> originalPayload;
}
