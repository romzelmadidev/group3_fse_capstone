package com.bank.cbs;

import com.bank.cbs.controller.CbsAuditController;
import com.bank.cbs.entity.audit.FailedTransactionAudit;
import com.bank.cbs.entity.audit.LedgerMutationAudit;
import com.bank.cbs.repository.audit.FailedTransactionAuditRepository;
import com.bank.cbs.repository.audit.LedgerMutationAuditRepository;
import com.bank.cbs.service.CbsAuditQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsAuditPaginationTest {

    @Mock
    private CbsAuditQueryService auditQueryService;

    @Mock
    private LedgerMutationAuditRepository ledgerMutationAuditRepository;

    @Mock
    private FailedTransactionAuditRepository failedTransactionAuditRepository;

    @Test
    @DisplayName("GET /api/v1/cbs/audit/accounts/{accountId}/mutations uses default pagination (page=0, size=50)")
    void testGetLedgerMutationsDefaultPagination() {
        CbsAuditController controller = new CbsAuditController(auditQueryService);

        LedgerMutationAudit mutation = LedgerMutationAudit.builder()
                .auditId(1L)
                .accountId("acc-001")
                .transactionId("TX-100")
                .mutationType("CREDIT")
                .mutationAmount(new BigDecimal("1000.00"))
                .beforeBalance(new BigDecimal("5000.00"))
                .afterBalance(new BigDecimal("6000.00"))
                .initiatorUserId("SYSTEM")
                .status("COMMITTED")
                .createdAt(Instant.now())
                .build();

        when(auditQueryService.getLedgerMutationsByAccount("acc-001", 0, 50))
                .thenReturn(List.of(mutation));

        ResponseEntity<List<LedgerMutationAudit>> response = controller.getLedgerMutations("acc-001", 0, 50);

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("TX-100", response.getBody().get(0).getTransactionId());
        verify(auditQueryService).getLedgerMutationsByAccount("acc-001", 0, 50);
    }

    @Test
    @DisplayName("GET /api/v1/cbs/audit/accounts/{accountId}/mutations uses custom pagination (page=1, size=10)")
    void testGetLedgerMutationsCustomPagination() {
        CbsAuditController controller = new CbsAuditController(auditQueryService);

        when(auditQueryService.getLedgerMutationsByAccount("acc-001", 1, 10))
                .thenReturn(List.of());

        ResponseEntity<List<LedgerMutationAudit>> response = controller.getLedgerMutations("acc-001", 1, 10);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
        verify(auditQueryService).getLedgerMutationsByAccount("acc-001", 1, 10);
    }

    @Test
    @DisplayName("GET /api/v1/cbs/audit/failed-transactions uses default pagination (page=0, size=20)")
    void testGetFailedTransactionsDefaultPagination() {
        CbsAuditController controller = new CbsAuditController(auditQueryService);

        FailedTransactionAudit failedTx = FailedTransactionAudit.builder()
                .incidentId("INC-001")
                .correlationId("CORR-001")
                .transactionId("TX-FAIL-1")
                .errorType("NETWORK_TIMEOUT")
                .errorCode("HTTP_504")
                .circuitBreakerState("OPEN")
                .payloadJson("{}")
                .replayStatus("PENDING_REPLAY")
                .failureTimestamp(Instant.now())
                .build();

        when(auditQueryService.getUnresolvedFailedTransactions(0, 20))
                .thenReturn(List.of(failedTx));

        ResponseEntity<List<FailedTransactionAudit>> response = controller.getFailedTransactions(0, 20);

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("INC-001", response.getBody().get(0).getIncidentId());
        verify(auditQueryService).getUnresolvedFailedTransactions(0, 20);
    }

    @Test
    @DisplayName("CbsAuditQueryService enforces safe bounds for negative page and excessive size")
    void testQueryServicePaginationBounds() {
        CbsAuditQueryService service = new CbsAuditQueryService(
                ledgerMutationAuditRepository,
                null,
                failedTransactionAuditRepository,
                null,
                null,
                null
        );

        when(ledgerMutationAuditRepository.findByAccountIdOrderByCreatedAtAsc(eq("acc-001"), any(Pageable.class)))
                .thenReturn(List.of());
        when(failedTransactionAuditRepository.findByReplayStatusOrderByFailureTimestampDesc(eq("PENDING_REPLAY"), any(Pageable.class)))
                .thenReturn(List.of());

        // Negative page (-5) and excessive size (500)
        service.getLedgerMutationsByAccount("acc-001", -5, 500);
        service.getUnresolvedFailedTransactions(-2, 1000);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ledgerMutationAuditRepository).findByAccountIdOrderByCreatedAtAsc(eq("acc-001"), pageableCaptor.capture());
        Pageable mutationPageable = pageableCaptor.getValue();
        assertEquals(0, mutationPageable.getPageNumber(), "Page should be normalized to 0");
        assertEquals(100, mutationPageable.getPageSize(), "Size should be capped at 100");

        ArgumentCaptor<Pageable> dlqPageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(failedTransactionAuditRepository).findByReplayStatusOrderByFailureTimestampDesc(eq("PENDING_REPLAY"), dlqPageableCaptor.capture());
        Pageable dlqPageable = dlqPageableCaptor.getValue();
        assertEquals(0, dlqPageable.getPageNumber(), "Page should be normalized to 0");
        assertEquals(100, dlqPageable.getPageSize(), "Size should be capped at 100");
    }
}
