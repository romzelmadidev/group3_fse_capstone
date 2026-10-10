package com.bank.orchestrator;

import com.bank.ledger.contracts.enums.TransactionStatus;
import com.bank.ledger.contracts.dto.events.TransferFailedToDlqEvent;
import com.bank.orchestrator.dto.TransferInitiationRequest;
import com.bank.orchestrator.service.CbsClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsClientServiceResilienceTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private CbsClientService cbsClientService;

    @BeforeEach
    void setUp() {
        WebClient.Builder webClientBuilder = WebClient.builder();
        cbsClientService = new CbsClientService(
                webClientBuilder,
                "http://localhost:8085",
                kafkaTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
    }

    @Test
    void testFallback_WithResponseStatusException_RethrowsImmediatelyWithoutDlq() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-INS-100", "1000-2000-3001", "1000-2000-3002",
                new BigDecimal("999999999.00"), "PHP", "Overdraft test",
                "DEV-1", "IDEMP-100", null, false
        );

        ResponseStatusException businessError = new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Posting failed in CBS: Insufficient funds"
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                cbsClientService.cbsPostingFallback(request, "TXN-INS-100", false, businessError)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("Insufficient funds"));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void testFallback_WithWebClient400BadRequest_TranslatesAndNeverPublishesDlq() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-INS-200", "1000-2000-3001", "1000-2000-3002",
                new BigDecimal("5000.00"), "PHP", "Invalid account test",
                "DEV-1", "IDEMP-200", null, false
        );

        String ofsErrorBody = "//-1,FAILURE,ERROR=ERROR,MESSAGE=Account balance not found for ID: ACC-INVALID";
        WebClientResponseException badReq = WebClientResponseException.create(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                HttpHeaders.EMPTY,
                ofsErrorBody.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                cbsClientService.cbsPostingFallback(request, "TXN-INS-200", false, badReq)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Account balance not found"));
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void testFallback_WithInfrastructureOutage_PublishesToDlqAndThrows503() {
        TransferInitiationRequest request = new TransferInitiationRequest(
                "TXN-OUTAGE-500", "1000-2000-3001", "1000-2000-3002",
                new BigDecimal("5000.00"), "PHP", "Timeout test",
                "DEV-1", "IDEMP-500", null, false
        );

        RuntimeException outage = new RuntimeException("Connection refused: connect to CBS core :8085 timed out");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                cbsClientService.cbsPostingFallback(request, "TXN-OUTAGE-500", false, outage)
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Core banking system is temporarily unavailable"));

        ArgumentCaptor<TransferFailedToDlqEvent> eventCaptor = ArgumentCaptor.forClass(TransferFailedToDlqEvent.class);
        verify(kafkaTemplate, times(1)).send(eq("banking.transfers.dlq"), eq("TXN-OUTAGE-500"), eventCaptor.capture());

        TransferFailedToDlqEvent captured = eventCaptor.getValue();
        assertEquals("TXN-OUTAGE-500", captured.getTransactionId());
        assertEquals("OPEN", captured.getCircuitBreakerState());
        assertEquals("CBS_CIRCUIT_BREAKER_OR_TIMEOUT", captured.getErrorType());
    }
}
