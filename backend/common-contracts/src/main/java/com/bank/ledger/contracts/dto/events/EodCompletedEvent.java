package com.bank.ledger.contracts.dto.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EodCompletedEvent {
    private String eventId;
    private String batchId;
    private String previousBusinessDate;
    private String newBusinessDate;
    private Instant completedAtUtc;
}
