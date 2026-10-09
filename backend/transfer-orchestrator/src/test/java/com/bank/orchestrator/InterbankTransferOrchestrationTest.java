package com.bank.orchestrator;

import com.bank.ledger.contracts.enums.TransactionStatus;
import com.bank.orchestrator.controller.InterbankIngressController;
import com.bank.orchestrator.dto.*;
import com.bank.orchestrator.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterbankTransferOrchestrationTest {

    @Mock
    private IdempotencyLockService idempotencyService;
    @Mock
    private CoolOffService coolOffService;
    @Mock
    private BiometricChallengeService biometricService;
    @Mock
    private RiskClientService riskService;
    @Mock
    private CbsClientService cbsService;
    @Mock
    private InterbankWireClient wireClient;

    private TransferOrchestrationService orchestrationService;
    private InterbankIngressController ingressController;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        orchestrationService = new TransferOrchestrationService(
                idempotencyService,
                coolOffService,
                biometricService,
                riskService,
                cbsService,
                wireClient,
                objectMapper
        );
        ingressController = new InterbankIngressController(cbsService, "AURABANK-SECURE-KEY-2026");
    }

    @Test
    void testOutwardInterbankTransfer_SuccessSettlement() {
        when(idempotencyService.acquireLock(anyString())).thenReturn(true);
        when(riskService.evaluateRisk(any())).thenReturn(new RiskScoreResponse("TXN-1", 10, "ALLOW", "No anomaly detected"));
        when(coolOffService.isInCoolOff(anyString())).thenReturn(false);

        // Mock CBS Leg 1 (Debit Sender -> Credit ACC-NOSTRO-BANK-B)
        when(cbsService.postToCbs(any(), anyString(), anyBoolean()))
                .thenReturn(new TransferInitiationResponse("TXN-1", TransactionStatus.Posted, new BigDecimal("1000.00"), "PHP", "acc-2002-chk-001", "ACC-NOSTRO-BANK-B", "Leg 1 Posted", false, 0L, false, null, Instant.now()));

        // Mock Leg 2 (Partner Bank Wire returns SETTLED)
        when(wireClient.dispatchWire(any(), isNull()))
                .thenReturn(new InterbankWireResponse("SETTLED", "B-TXN-9999", Instant.now().toString(), "Wire settled"));

        TransferInitiationRequest req = new TransferInitiationRequest(
                "TXN-1",
                "acc-2002-chk-001",
                "ACC-B-2002",
                new BigDecimal("1000.00"),
                "PHP",
                "Interbank transfer to Bank B",
                "DEVICE-01",
                "IDEMP-1",
                null,
                false,
                "BANK_B",
                "Alice Reyes",
                null
        );

        TransferInitiationResponse resp = orchestrationService.initiateTransfer(req);

        assertNotNull(resp);
        assertEquals(TransactionStatus.Posted, resp.status());
        assertTrue(resp.message().contains("BANK_B"));
        assertTrue(resp.message().contains("B-TXN-9999"));

        // Verify Leg 1 posted to Nostro
        verify(cbsService, times(1)).postToCbs(argThat(r -> "ACC-NOSTRO-BANK-B".equals(r.destinationAccountId())), eq("TXN-1"), eq(false));
        // Verify wire dispatched
        verify(wireClient, times(1)).dispatchWire(any(), isNull());
        // Verify NO reversal occurred
        verify(cbsService, never()).executeCompensatingReversal(anyString(), anyString());
    }

    @Test
    void testOutwardInterbankTransfer_FailureTriggersSagaCompensatingReversal() {
        when(idempotencyService.acquireLock(anyString())).thenReturn(true);
        when(riskService.evaluateRisk(any())).thenReturn(new RiskScoreResponse("TXN-FAIL", 10, "ALLOW", "No anomaly"));
        when(coolOffService.isInCoolOff(anyString())).thenReturn(false);

        // Leg 1 succeeds
        when(cbsService.postToCbs(any(), anyString(), anyBoolean()))
                .thenReturn(new TransferInitiationResponse("TXN-FAIL", TransactionStatus.Posted, new BigDecimal("1000.00"), "PHP", "acc-2002-chk-001", "ACC-NOSTRO-BANK-B", "Leg 1 Posted", false, 0L, false, null, Instant.now()));

        // Leg 2 fails (e.g., Timeout or Partner 500)
        when(wireClient.dispatchWire(any(), isNull()))
                .thenThrow(new RuntimeException("Connection timed out waiting for Bank B gateway"));

        TransferInitiationRequest req = new TransferInitiationRequest(
                "TXN-FAIL",
                "acc-2002-chk-001",
                "ACC-B-2002",
                new BigDecimal("1000.00"),
                "PHP",
                "Interbank transfer to Bank B",
                "DEVICE-01",
                "IDEMP-FAIL",
                null,
                false,
                "BANK_B",
                "Alice Reyes",
                null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> orchestrationService.initiateTransfer(req));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Saga rollback"));

        // CRITICAL: Assert that CBS Compensating Reversal was invoked to refund Alice!
        verify(cbsService, times(1)).executeCompensatingReversal(eq("TXN-FAIL"), anyString());
    }

    @Test
    void testInwardInterbankTransfer_SuccessSettlement() {
        // Mock beneficiary account check
        when(cbsService.getAccountBalance("acc-2002-chk-001"))
                .thenReturn(Map.of("accountId", "acc-2002-chk-001", "accountNumber", "100100005678", "availableBalance", "8500000.00"));

        InterbankWireRequest wireReq = new InterbankWireRequest(
                "WIRE-IN-01",
                "BANK_B",
                "Bob Johnson",
                "ACC-B-2002",
                "acc-2002-chk-001",
                new BigDecimal("2500.00"),
                "PHP",
                Instant.now().toString()
        );

        ResponseEntity<InterbankWireResponse> response = ingressController.handleInwardWire("AURABANK-SECURE-KEY-2026", wireReq);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("SETTLED", response.getBody().status());
        assertTrue(response.getBody().partnerReference().startsWith("AURA-INW-"));

        // Verify Leg 3 on CBS: Nostro buffer debited, beneficiary credited
        verify(cbsService, times(1)).postToCbs(argThat(r ->
                "ACC-NOSTRO-BANK-B".equals(r.sourceAccountId()) &&
                "acc-2002-chk-001".equals(r.destinationAccountId()) &&
                new BigDecimal("2500.00").compareTo(r.amount()) == 0
        ), anyString(), eq(false));
    }
}
