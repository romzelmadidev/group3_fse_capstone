package com.bank.ledger.contracts.dto.events;

import com.bank.ledger.contracts.enums.ActorType;
import com.bank.ledger.contracts.enums.TransactionStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionStatusChangedEvent {
    private String eventId;
    private String eventType;
    private String version;
    private String transactionId;
    private TransactionStatus fromStatus;
    private TransactionStatus toStatus;
    private String changeReason;
    private String reasonDetails;
    private String actorId;
    private ActorType actorType;
    private Instant changedAt;
    private Map<String, Object> metadata;
}
