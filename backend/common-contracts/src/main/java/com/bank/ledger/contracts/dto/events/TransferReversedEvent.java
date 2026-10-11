package com.bank.ledger.contracts.dto.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransferReversedEvent {
    private String eventId;
    private String eventType;
    private String version;
    private String ticketId;
    private String originalTransactionId;
    private String reversalTransactionId;
    private String cbsReference;
    private String beneficiaryAccountId;
    private String originalSenderAccountId;
    private BigDecimal amount;
    private String currency;
    private String makerId;
    private String checkerId;
    private BigDecimal beneficiaryBalanceAfter;
    private BigDecimal originalSenderBalanceAfter;
    private Instant executedAtUtc;
}
