package com.bank.orchestrator;

import com.bank.ledger.contracts.enums.TransactionStatus;
import com.bank.orchestrator.dto.RiskScoreRequest;
import com.bank.orchestrator.exception.LimitCheckUnavailableException;
import com.bank.orchestrator.exception.LimitExceededException;
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
import java.util.Random;

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

    @Mock
    private LimitClientService limitClient;

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
                limitClient,
                objectMapper
        );
        when(idempotencyService.acquireLock(anyString())).thenReturn(true);
        lenient().when(limitClient.evaluate(anyString(), any(), any()))
                .thenReturn(new LimitClientService.LimitEvaluation("lock-token"));
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
        when(biometricService.generateChallenge(eq("TXN-BIO"), eq("ACC-DEST"), eq(new BigDecimal("75000.00")), eq("PHP")))
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
        verify(coolOffService, times(1)).putInCoolOff(eq("TXN-COOL"), anyString());
        verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
    }

    // Feature: kyc-tiered-transfer-limits, Property 8: Non-allowed outcomes have no side effects
    // Validates: Requirements 4.7, 4.10, 10.3
    @Test
    void property8_nonAllowedLimitOutcomes_haveNoSideEffects() {
        Random random = new Random(0);
        BigDecimal[] boundaryAmounts = {
                new BigDecimal("0.01"), new BigDecimal("49999.99"), new BigDecimal("50000.00"),
                new BigDecimal("50000.01"), new BigDecimal("249999.99"), new BigDecimal("250000.00"),
                new BigDecimal("250000.01"), new BigDecimal("5000000.00")
        };
        String[] capKinds = {"PER_TRANSACTION", "DAILY", "MONTHLY", "RESTRICTED"};
        String[] tiers = {"TIER_1", "TIER_2", "TIER_3"};
        String[] unavailableReasons = {
                "Transfer limits could not be verified", // timeout / 5xx
                "Limit service returned an empty body", // malformed body
                "Limit service allowed without a lock token",
                "Limit service rejected without breach details",
                "Customer limit lock timed out" // lock timeout
        };

        int iterations = 300;
        for (int i = 0; i < iterations; i++) {
            BigDecimal amount = i < boundaryAmounts.length
                    ? boundaryAmounts[i]
                    : BigDecimal.valueOf(1 + random.nextInt(60_000_000), 2)
                            .add(random.nextBoolean() ? BigDecimal.valueOf(25_000L) : BigDecimal.ZERO);
            ResponseStatusException thrown;
            if (random.nextBoolean()) {
                thrown = new LimitExceededException(
                        capKinds[random.nextInt(capKinds.length)],
                        BigDecimal.valueOf(random.nextInt(1_000_000)),
                        BigDecimal.valueOf(random.nextInt(1_000_000)),
                        tiers[random.nextInt(tiers.length)],
                        random.nextBoolean());
            } else {
                thrown = new LimitCheckUnavailableException(
                        unavailableReasons[random.nextInt(unavailableReasons.length)],
                        random.nextBoolean() ? new RuntimeException("cause") : null);
            }

            clearInvocations(idempotencyService, coolOffService, biometricService, riskService, cbsService, limitClient);
            // doThrow avoids invoking the previous iteration's throwing stub while re-stubbing
            lenient().doThrow(thrown).when(limitClient).evaluate(anyString(), any(), any());

            TransferInitiationRequest request = new TransferInitiationRequest(
                    "TXN-P8-" + i, "ACC-SOURCE", "ACC-DEST", amount, "PHP", "Property 8",
                    "DEV-1", "IDEMP-P8-" + i, null, false);

            ResponseStatusException actual = assertThrows(ResponseStatusException.class,
                    () -> orchestrationService.initiateTransfer(request), "iteration " + i + " amount " + amount);

            assertSame(thrown, actual, "iteration " + i);
            assertEquals(thrown instanceof LimitExceededException ? 402 : 503, actual.getStatusCode().value());
            verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
            verify(idempotencyService, never()).evictBalanceCache(any(), any());
            verify(biometricService, never()).generateChallenge(any(), any(), any(), any());
            verify(coolOffService, never()).putInCoolOff(any(), any());
            verify(riskService, never()).evaluateRisk(any());
            verify(limitClient, never()).release(any());
            verify(idempotencyService, times(1)).releaseLock("IDEMP-P8-" + i);
        }
    }

    // Feature: kyc-tiered-transfer-limits, Property 8: Non-allowed outcomes have no side effects
    // Validates: Requirements 4.7, 4.10, 10.3 (a held lock is released when a later step fails)
    @Test
    void property8_downstreamFailureAfterAllow_releasesLimitLockAndSkipsEviction() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-P8-CBS", "ACC-SOURCE", "ACC-DEST",
                new BigDecimal("5000.00"), "PHP", "CBS failure",
                "DEV-1", "IDEMP-P8-CBS", null, false);

        when(riskService.evaluateRisk(any(RiskScoreRequest.class)))
                .thenReturn(new RiskScoreResponse("TXN-P8-CBS", 10, "ALLOW", "Low risk"));
        when(cbsService.postToCbs(any(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("CBS down"));

        ResponseStatusException actual = assertThrows(ResponseStatusException.class,
                () -> orchestrationService.initiateTransfer(request));

        assertEquals(500, actual.getStatusCode().value());
        verify(idempotencyService, never()).evictBalanceCache(any(), any());
        verify(limitClient, times(1)).release("lock-token");
        verify(idempotencyService, times(1)).releaseLock("IDEMP-P8-CBS");
    }

    // Feature: kyc-tiered-transfer-limits, Property 10: Risk tiers unchanged for allowed transfers
    // Validates: Requirements 10.1, 10.2
    @Test
    void property10_allowedLimitOutcome_leavesRiskTierBehaviourUnchanged() {
        Random random = new Random(0);
        BigDecimal[] boundaryAmounts = {
                new BigDecimal("49999.99"), new BigDecimal("50000.00"), new BigDecimal("250000.00"),
                new BigDecimal("500000.00"), new BigDecimal("0.01"), new BigDecimal("249999.99")
        };
        String[] decisions = {"ALLOW", "ADVISORY_WARNING", "BLOCK"};
        BigDecimal biometricThreshold = new BigDecimal("50000.00");
        BigDecimal coolOffThreshold = new BigDecimal("250000.00");

        int iterations = 300;
        for (int i = 0; i < iterations; i++) {
            // First 18 iterations cover every boundary amount under every decision deterministically.
            boolean boundaryPhase = i < boundaryAmounts.length * decisions.length;
            BigDecimal amount = boundaryPhase
                    ? boundaryAmounts[i % boundaryAmounts.length]
                    : (random.nextInt(4) == 0
                            ? boundaryAmounts[random.nextInt(boundaryAmounts.length)]
                            : BigDecimal.valueOf(1 + random.nextInt(60_000_000), 2));
            String decision = boundaryPhase
                    ? decisions[i / boundaryAmounts.length]
                    : decisions[random.nextInt(decisions.length)];
            int score = random.nextInt(5) == 0 ? 59 + random.nextInt(2) : random.nextInt(101);
            boolean acknowledged = random.nextBoolean();
            String signature = random.nextBoolean() ? null : "SIG-" + i;
            boolean inCoolOff = random.nextBoolean();

            String txId = "TXN-P10-" + i;
            String idempKey = "IDEMP-P10-" + i;
            String token = "lock-token-" + i;

            clearInvocations(idempotencyService, coolOffService, biometricService, riskService, cbsService, limitClient);
            // doReturn avoids invoking the previous iteration's stubs while re-stubbing
            lenient().doReturn(new LimitClientService.LimitEvaluation(token))
                    .when(limitClient).evaluate(anyString(), any(), any());
            lenient().doReturn(new RiskScoreResponse(txId, score, decision, "risk reason"))
                    .when(riskService).evaluateRisk(any(RiskScoreRequest.class));
            lenient().doReturn(inCoolOff).when(coolOffService).isInCoolOff(anyString());
            lenient().doReturn("CHALLENGE-" + i).when(biometricService)
                    .generateChallenge(anyString(), anyString(), any(), anyString());
            TransferInitiationResponse cbsResponse = new TransferInitiationResponse(
                    txId, TransactionStatus.Posted, amount, "PHP", "ACC-SOURCE", "ACC-DEST",
                    "Success", false, 0L, false, null, Instant.now());
            lenient().doReturn(cbsResponse).when(cbsService).postToCbs(any(), anyString(), anyBoolean());

            TransferInitiationRequest request = new TransferInitiationRequest(
                    txId, "ACC-SOURCE", "ACC-DEST", amount, "PHP", "Property 10",
                    "DEV-1", idempKey, signature, acknowledged);

            // Oracle of the pre-limit-stage orchestration rules
            boolean requiresBiometric = amount.compareTo(biometricThreshold) >= 0 || score >= 60;
            boolean unsigned = signature == null;
            String context = "iteration " + i + " amount=" + amount + " decision=" + decision + " score=" + score
                    + " ack=" + acknowledged + " signed=" + !unsigned + " inCoolOff=" + inCoolOff;

            boolean posted = false;
            if ("BLOCK".equals(decision)) {
                ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                        () -> orchestrationService.initiateTransfer(request), context);
                assertEquals(403, thrown.getStatusCode().value(), context);
                verify(biometricService, never()).generateChallenge(any(), any(), any(), any());
                verify(coolOffService, never()).putInCoolOff(any(), any());
            } else if ("ADVISORY_WARNING".equals(decision) && !acknowledged) {
                TransferInitiationResponse response = orchestrationService.initiateTransfer(request);
                assertEquals(TransactionStatus.Processing, response.status(), context);
                assertTrue(response.message().startsWith("SCAM_ADVISORY_WARNING"), context);
                assertFalse(response.biometricRequired(), context);
                assertFalse(response.coolingOffRequired(), context);
                verify(biometricService, never()).generateChallenge(any(), any(), any(), any());
                verify(coolOffService, never()).putInCoolOff(any(), any());
            } else if (requiresBiometric && unsigned) {
                TransferInitiationResponse response = orchestrationService.initiateTransfer(request);
                assertEquals(TransactionStatus.Authorized, response.status(), context);
                assertTrue(response.biometricRequired(), context);
                assertEquals("CHALLENGE-" + i, response.biometricChallenge(), context);
                assertFalse(response.coolingOffRequired(), context);
                verify(biometricService, times(1)).generateChallenge(eq(txId), eq("ACC-DEST"), eq(amount), eq("PHP"));
                verify(coolOffService, times(1)).putInCoolOff(eq("bio:pending:" + txId), anyString());
            } else if (amount.compareTo(coolOffThreshold) >= 0 && !inCoolOff) {
                TransferInitiationResponse response = orchestrationService.initiateTransfer(request);
                assertEquals(TransactionStatus.Reserved, response.status(), context);
                assertTrue(response.coolingOffRequired(), context);
                assertEquals(600L, response.coolingOffExpiresInSeconds(), context);
                assertFalse(response.biometricRequired(), context);
                verify(coolOffService, times(1)).putInCoolOff(eq(txId), anyString());
                verify(biometricService, never()).generateChallenge(any(), any(), any(), any());
            } else {
                TransferInitiationResponse response = orchestrationService.initiateTransfer(request);
                assertSame(cbsResponse, response, context);
                posted = true;
            }

            if (posted) {
                verify(cbsService, times(1)).postToCbs(eq(request), eq(txId), eq(inCoolOff));
            } else {
                verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
            }

            // The limit stage always runs first, then risk, and the lock is released before the idempotency lock.
            org.mockito.InOrder order = inOrder(limitClient, riskService, cbsService, idempotencyService);
            order.verify(limitClient).evaluate(eq(txId), eq("ACC-SOURCE"), eq(amount));
            order.verify(riskService).evaluateRisk(any(RiskScoreRequest.class));
            if (posted) {
                order.verify(cbsService).postToCbs(eq(request), eq(txId), eq(inCoolOff));
            }
            order.verify(limitClient).release(token);
            order.verify(idempotencyService).releaseLock(idempKey);
            verify(limitClient, times(1)).evaluate(anyString(), any(), any());
            verify(limitClient, times(1)).release(token);
        }
    }
}
