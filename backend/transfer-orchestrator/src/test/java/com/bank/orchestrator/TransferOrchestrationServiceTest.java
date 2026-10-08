package com.bank.orchestrator;

import com.bank.ledger.contracts.enums.TransactionStatus;
import com.bank.orchestrator.dto.RiskScoreRequest;
import com.bank.orchestrator.dto.RiskScoreResponse;
import com.bank.orchestrator.dto.TransferInitiationRequest;
import com.bank.orchestrator.dto.TransferInitiationResponse;
import com.bank.orchestrator.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferOrchestrationServiceTest {

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

    private ObjectMapper objectMapper;
    private TransferOrchestrationService orchestrationService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        orchestrationService = new TransferOrchestrationService(
                idempotencyService,
                coolOffService,
                biometricService,
                riskService,
                cbsService,
                objectMapper
        );
        when(idempotencyService.acquireLock(anyString())).thenReturn(true);
    }

    @Test
    void testStandardTransferExecution_PassesAllGates() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-101", "ACC-SOURCE", "ACC-DEST",
                new BigDecimal("5000.00"), "PHP", "Family support",
                "DEV-1", "IDEMP-101", null, false
        );

        when(riskService.evaluateRisk(any(RiskScoreRequest.class)))
                .thenReturn(new RiskScoreResponse("TXN-101", 10, "ALLOW", "Low risk"));

        when(cbsService.postToCbs(eq(request), eq("TXN-101"), eq(false)))
                .thenReturn(new TransferInitiationResponse(
                        "TXN-101", TransactionStatus.Posted, new BigDecimal("5000.00"),
                        "PHP", "ACC-SOURCE", "ACC-DEST", "Success", false, 0L, false, null, Instant.now()
                ));

        TransferInitiationResponse response = orchestrationService.initiateTransfer(request);

        assertNotNull(response);
        assertEquals(TransactionStatus.Posted, response.status());
        assertFalse(response.biometricRequired());
        assertFalse(response.coolingOffRequired());
        verify(cbsService, times(1)).postToCbs(any(), anyString(), eq(false));
    }

    @Test
    void testFraudCircuitBreaker_BlocksTransfer() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-BLOCK", "ACC-SOURCE", "ACC-FRAUD",
                new BigDecimal("50000.00"), "PHP", "Mule transfer",
                "DEV-NEW", "IDEMP-BLOCK", null, false
        );

        when(riskService.evaluateRisk(any(RiskScoreRequest.class)))
                .thenReturn(new RiskScoreResponse("TXN-BLOCK", 95, "BLOCK", "Known fraudulent beneficiary account"));

        assertThrows(ResponseStatusException.class, () -> orchestrationService.initiateTransfer(request));
        verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
    }

    @Test
    void testHighValueTransfer_RequiresBiometricChallenge() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-BIO", "ACC-SOURCE", "ACC-DEST",
                new BigDecimal("75000.00"), "PHP", "Large transfer",
                "DEV-1", "IDEMP-BIO", null, false
        );

        when(riskService.evaluateRisk(any(RiskScoreRequest.class)))
                .thenReturn(new RiskScoreResponse("TXN-BIO", 20, "ALLOW", "Normal risk"));
        when(biometricService.generateChallenge("TXN-BIO"))
                .thenReturn("CHALLENGE-TOKEN-XYZ");

        TransferInitiationResponse response = orchestrationService.initiateTransfer(request);

        assertNotNull(response);
        assertTrue(response.biometricRequired());
        assertEquals("CHALLENGE-TOKEN-XYZ", response.biometricChallenge());
        assertEquals(TransactionStatus.Authorized, response.status());
        verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
    }

    @Test
    void testVeryHighValueTransfer_TriggersAntiScamCoolingOffPeriod() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-COOL", "ACC-SOURCE", "ACC-DEST",
                new BigDecimal("300000.00"), "PHP", "Real estate reservation",
                "DEV-1", "IDEMP-COOL", "VALID-SIGNATURE", false
        );

        when(riskService.evaluateRisk(any(RiskScoreRequest.class)))
                .thenReturn(new RiskScoreResponse("TXN-COOL", 20, "ALLOW", "Low risk"));
        when(coolOffService.isInCoolOff("TXN-COOL")).thenReturn(false);

        TransferInitiationResponse response = orchestrationService.initiateTransfer(request);

        assertNotNull(response);
        assertTrue(response.coolingOffRequired());
        assertEquals(600L, response.coolingOffExpiresInSeconds());
        assertEquals(TransactionStatus.Reserved, response.status());
        verify(cbsService, times(1)).placeHold(eq("ACC-SOURCE"), eq(new BigDecimal("300000.00")), eq("TXN-COOL"));
        verify(coolOffService, times(1)).putInCoolOff(eq("TXN-COOL"), anyString());
        verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
    }
}
