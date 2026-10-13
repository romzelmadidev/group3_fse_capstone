package com.bank.cbs;

import com.bank.cbs.entity.audit.FailedTransactionAudit;
import com.bank.cbs.repository.audit.FailedTransactionAuditRepository;
import com.bank.cbs.repository.audit.LedgerMutationAuditRepository;
import com.bank.cbs.repository.audit.TransactionStatusAuditRepository;
import com.bank.cbs.service.CbsAuditSelfConsumptionService;
import com.bank.cbs.service.MerkleTreeService;
import com.bank.ledger.contracts.dto.events.TransferFailedToDlqEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsAuditSelfConsumptionServiceTest {

    @Mock
    private LedgerMutationAuditRepository ledgerMutationAuditRepository;

    @Mock
    private TransactionStatusAuditRepository transactionStatusAuditRepository;

    @Mock
    private FailedTransactionAuditRepository failedTransactionAuditRepository;

    @Mock
    private MerkleTreeService merkleTreeService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CbsAuditSelfConsumptionService service;

    @BeforeEach
    void setUp() {
        service = new CbsAuditSelfConsumptionService(
                ledgerMutationAuditRepository,
                transactionStatusAuditRepository,
                failedTransactionAuditRepository,
                objectMapper,
                merkleTreeService
        );
    }

    @Test
    @DisplayName("First DLQ event for a transaction creates a new audit entry")
    void firstDlqEventCreatesNewRecord() {
        TransferFailedToDlqEvent event = TransferFailedToDlqEvent.builder()
                .incidentId("inc-100")
                .correlationId("tx-100")
                .transactionId("tx-100")
                .errorType("CBS_DATABASE_UNAVAILABLE")
                .errorCode("DB_UNAVAILABLE")
                .originalPayload(Map.of("amount", 1000))
                .failureTimestampUtc(Instant.now())
                .build();

        when(failedTransactionAuditRepository.findFirstByTransactionIdOrderByFailureTimestampDesc("tx-100"))
                .thenReturn(Optional.empty());

        ConsumerRecord<String, Object> record = new ConsumerRecord<>("banking.transfers.dlq", 0, 0, "tx-100", event);
        service.consumeDlqEvent(record);

        ArgumentCaptor<FailedTransactionAudit> captor = ArgumentCaptor.forClass(FailedTransactionAudit.class);
        verify(failedTransactionAuditRepository).save(captor.capture());

        FailedTransactionAudit saved = captor.getValue();
        assertEquals("inc-100", saved.getIncidentId());
        assertEquals("tx-100", saved.getTransactionId());
        assertEquals("PENDING_REPLAY", saved.getReplayStatus());
        assertEquals("CBS_DATABASE_UNAVAILABLE", saved.getErrorType());
    }

    @Test
    @DisplayName("Subsequent DLQ event for existing transaction updates record in-place without duplicating")
    void subsequentDlqEventUpdatesExistingRecordInPlace() {
        FailedTransactionAudit existing = FailedTransactionAudit.builder()
                .incidentId("inc-original")
                .transactionId("tx-200")
                .errorType("INITIAL_FAILURE")
                .errorCode("ERR_1")
                .replayStatus("PENDING_REPLAY")
                .failureTimestamp(Instant.now().minusSeconds(60))
                .build();

        when(failedTransactionAuditRepository.findFirstByTransactionIdOrderByFailureTimestampDesc("tx-200"))
                .thenReturn(Optional.of(existing));

        TransferFailedToDlqEvent retryEvent = TransferFailedToDlqEvent.builder()
                .incidentId("inc-new-retry")
                .correlationId("tx-200")
                .transactionId("tx-200")
                .errorType("RETRY_FAILURE_TIMEOUT")
                .errorCode("TIMEOUT")
                .originalPayload(Map.of("amount", 500))
                .failureTimestampUtc(Instant.now())
                .build();

        ConsumerRecord<String, Object> record = new ConsumerRecord<>("banking.transfers.dlq", 0, 1, "tx-200", retryEvent);
        service.consumeDlqEvent(record);

        ArgumentCaptor<FailedTransactionAudit> captor = ArgumentCaptor.forClass(FailedTransactionAudit.class);
        verify(failedTransactionAuditRepository).save(captor.capture());

        FailedTransactionAudit saved = captor.getValue();
        // Crucial invariant: Keeps original incident ID, does not insert new row
        assertEquals("inc-original", saved.getIncidentId());
        assertEquals("tx-200", saved.getTransactionId());
        assertEquals("RETRY_FAILURE_TIMEOUT", saved.getErrorType());
        assertEquals("TIMEOUT", saved.getErrorCode());
        assertEquals("PENDING_REPLAY", saved.getReplayStatus());
    }
}
