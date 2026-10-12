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

        // Mock Leg 2 (Partner Bank Wire returns ACCEPTED)
        when(wireClient.dispatchWire(any(), isNull()))
                .thenReturn(InterbankWireResponse.accepted("MSG-001", "IB-20261012-999999", "B*** M***"));

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
                "COOBS",
                "Alice Reyes",
                null
        );

        TransferInitiationResponse resp = orchestrationService.initiateTransfer(req);

        assertNotNull(resp);
        assertEquals(TransactionStatus.Posted, resp.status());
        assertTrue(resp.message().contains("COOBS"));
        assertTrue(resp.message().contains("IB-20261012-999999"));

        // Verify Leg 1 posted to Nostro
        verify(cbsService, times(1)).postToCbs(argThat(r -> "ACC-NOSTRO-BANK-B".equals(r.destinationAccountId())), eq("TXN-1"), eq(false));
        // Verify wire dispatched
        verify(wireClient, times(1)).dispatchWire(any(), isNull());
        // Verify NO reversal occurred
        verify(cbsService, never()).executeCompensatingReversal(anyString(), anyString());
    }

    @Test
    void testOutwardInterbankTransfer_TimeoutTriggersSagaCompensatingReversalAndDlq() {
        when(idempotencyService.acquireLock(anyString())).thenReturn(true);
        when(riskService.evaluateRisk(any())).thenReturn(new RiskScoreResponse("TXN-FAIL", 10, "ALLOW", "No anomaly"));
        when(coolOffService.isInCoolOff(anyString())).thenReturn(false);

        // Leg 1 succeeds
        when(cbsService.postToCbs(any(), anyString(), anyBoolean()))
                .thenReturn(new TransferInitiationResponse("TXN-FAIL", TransactionStatus.Posted, new BigDecimal("1000.00"), "PHP", "acc-2002-chk-001", "ACC-NOSTRO-BANK-B", "Leg 1 Posted", false, 0L, false, null, Instant.now()));

        // Leg 2 fails (Timeout)
        when(wireClient.dispatchWire(any(), isNull()))
                .thenThrow(new RuntimeException("Connection timed out waiting for CooBS gateway"));

        TransferInitiationRequest req = new TransferInitiationRequest(
                "TXN-FAIL",
                "acc-2002-chk-001",
                "ACC-COOBS-2002",
                new BigDecimal("1000.00"),
                "PHP",
                "Interbank transfer to CooBS",
                "DEVICE-01",
                "IDEMP-FAIL",
                null,
                false,
                "COOBS",
                "Alice Reyes",
                null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> orchestrationService.initiateTransfer(req));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        // Verify sanitized banking message (zero internal Saga/timing jargon leaked)
        assertTrue(ex.getReason().contains("Any deducted funds have been returned to your account"));
        assertFalse(ex.getReason().contains("Saga rollback"));
        assertFalse(ex.getReason().contains("4.0s"));

        // CRITICAL: Assert that CBS Compensating Reversal was invoked to refund Alice!
        verify(cbsService, times(1)).executeCompensatingReversal(eq("TXN-FAIL"), anyString());
        // CRITICAL: Assert that incident was published to Kafka DLQ!
        verify(cbsService, times(1)).publishDlqEvent(eq("TXN-FAIL"), any(), eq("PARTNER_TIMEOUT"), anyString(), anyString(), any());
    }

    @Test
    void testOutwardInterbankTransfer_PartnerAccountNotFound_RefundsCustomerWithoutDlqPollution() {
        when(idempotencyService.acquireLock(anyString())).thenReturn(true);
        when(riskService.evaluateRisk(any())).thenReturn(new RiskScoreResponse("TXN-REJ", 10, "ALLOW", "No anomaly"));
        when(coolOffService.isInCoolOff(anyString())).thenReturn(false);

        // Leg 1 succeeds
        when(cbsService.postToCbs(any(), anyString(), anyBoolean()))
                .thenReturn(new TransferInitiationResponse("TXN-REJ", TransactionStatus.Posted, new BigDecimal("1000.00"), "PHP", "acc-2002-chk-001", "ACC-NOSTRO-BANK-B", "Leg 1 Posted", false, 0L, false, null, Instant.now()));

        // Leg 2 partner rejection (ACCOUNT_NOT_FOUND)
        when(wireClient.dispatchWire(any(), isNull()))
                .thenReturn(InterbankWireResponse.rejected("MSG-REJ", "ACCOUNT_NOT_FOUND", "No account with that number."));

        TransferInitiationRequest req = new TransferInitiationRequest(
                "TXN-REJ",
                "acc-2002-chk-001",
                "ACC-COOBS-9999",
                new BigDecimal("1000.00"),
                "PHP",
                "Interbank transfer to CooBS",
                "DEVICE-01",
                "IDEMP-REJ",
                null,
                false,
                "COOBS",
                "Alice Reyes",
                null
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> orchestrationService.initiateTransfer(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("The recipient bank could not find this account number"));
        assertTrue(ex.getReason().contains("Any deducted funds have been returned to your account"));

        // Verify CBS Compensating Reversal was invoked to refund customer
        verify(cbsService, times(1)).executeCompensatingReversal(eq("TXN-REJ"), anyString());
        // Verify DLQ was NEVER called for terminal business validation rejection (Zero DLQ pollution)
        verify(cbsService, never()).publishDlqEvent(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void testInwardInterbankTransfer_SuccessSettlement() {
        // Mock beneficiary account check
        when(cbsService.getAccountBalance("ACC_10000001"))
                .thenReturn(Map.of("accountId", "ACC_10000001", "accountNumber", "ACC_10000001", "availableBalance", "8500000.00", "status", "ACTIVE"));

        InterbankWireRequest wireReq = new InterbankWireRequest(
                "0b6c2f6e-6d1a-4d5c-9d1f-0f3a5e7b9c21",
                "COOBS",
                "AURABANK",
                new InterbankWireRequest.PartyDetails("OTHER_10000001", "John Doe"),
                new InterbankWireRequest.PartyDetails("ACC_10000001", "Juan Dela Cruz"),
                "2500.00",
                "PHP",
                "Payment for services",
                "TX-20261009-8F3A21C0",
                "2026-10-09T04:30:00Z"
        );

        ResponseEntity<InterbankWireResponse> response = ingressController.handleInwardWire("AURABANK-SECURE-KEY-2026", wireReq);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ACCEPTED", response.getBody().status());
        assertEquals("0b6c2f6e-6d1a-4d5c-9d1f-0f3a5e7b9c21", response.getBody().messageId());
        assertTrue(response.getBody().receiverReference().startsWith("IB-"));
        assertEquals("J*** D*** C***", response.getBody().beneficiaryName());
        assertNotNull(response.getBody().creditedAt());

        // Verify Leg 3 on CBS: Nostro buffer debited, beneficiary credited
        verify(cbsService, times(1)).postToCbs(argThat(r ->
                "ACC-NOSTRO-BANK-B".equals(r.sourceAccountId()) &&
                "ACC_10000001".equals(r.destinationAccountId()) &&
                new BigDecimal("2500.00").compareTo(r.amount()) == 0
        ), anyString(), eq(false));
    }

    @Test
    void testInwardInterbankTransfer_BeneficiaryNotFound_ReturnsRejected() {
        when(cbsService.getAccountBalance("ACC_UNKNOWN_9999"))
                .thenReturn(Map.of()); // Not found

        InterbankWireRequest wireReq = new InterbankWireRequest(
                "0b6c2f6e-6d1a-4d5c-9d1f-0f3a5e7b9c21",
                "COOBS",
                "AURABANK",
                new InterbankWireRequest.PartyDetails("OTHER_10000001", "John Doe"),
                new InterbankWireRequest.PartyDetails("ACC_UNKNOWN_9999", "Unknown Person"),
                "2500.00",
                "PHP",
                "Payment for services",
                "TX-20261009-8F3A21C0",
                "2026-10-09T04:30:00Z"
        );

        ResponseEntity<InterbankWireResponse> response = ingressController.handleInwardWire("AURABANK-SECURE-KEY-2026", wireReq);

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("REJECTED", response.getBody().status());
        assertEquals("ACCOUNT_NOT_FOUND", response.getBody().reasonCode());
        assertEquals("No account with that number.", response.getBody().reasonMessage());
        assertNotNull(response.getBody().rejectedAt());

        // Verify CBS was NEVER credited
        verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
    }

    @Test
    void testInwardInterbankTransfer_InvalidApiKey_ReturnsUnauthorized() {
        InterbankWireRequest wireReq = new InterbankWireRequest(
                "0b6c2f6e-6d1a-4d5c-9d1f-0f3a5e7b9c21",
                "COOBS",
                "AURABANK",
                new InterbankWireRequest.PartyDetails("OTHER_10000001", "John Doe"),
                new InterbankWireRequest.PartyDetails("1000-2000-3001", "Juan Dela Cruz"),
                "2500.00",
                "PHP",
                "Payment for services",
                "TX-20261009-8F3A21C0",
                "2026-10-09T04:30:00Z"
        );

        ResponseEntity<InterbankWireResponse> response = ingressController.handleInwardWire("FORGED-API-KEY", wireReq);

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("REJECTED", response.getBody().status());
        assertEquals("UNAUTHORIZED", response.getBody().reasonCode());

        // Zero CBS touches
        verify(cbsService, never()).getAccountBalance(anyString());
        verify(cbsService, never()).postToCbs(any(), anyString(), anyBoolean());
    }
}
