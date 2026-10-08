package com.bank.orchestrator.service;

import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.dto.events.TransferFailedToDlqEvent;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import com.bank.orchestrator.dto.TransferInitiationRequest;
import com.bank.orchestrator.dto.TransferInitiationResponse;
import com.bank.ledger.contracts.enums.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CbsClientService {

    private static final Logger log = LoggerFactory.getLogger(CbsClientService.class);
    private static final String DLQ_TOPIC = "banking.transfers.dlq";

    private final WebClient webClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public CbsClientService(
            WebClient.Builder webClientBuilder,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void placeHold(String accountId, BigDecimal amount, String txId) {
        log.info("Core banking hold endpoints decommissioned; 1-step atomic transfer enforced. No-op for txId={}", txId);
    }

    public void releaseHold(String accountId, BigDecimal amount, String txId) {
        log.info("Core banking hold endpoints decommissioned; 1-step atomic transfer enforced. No-op for txId={}", txId);
    }

    public List<AccountTransactionDto> getAccountTransactions(String accountId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        log.info("Querying CBS past transactions for accountId={} (page={}, size={})", accountId, safePage, safeSize);
        try {
            String restResp = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/cbs/accounts/{accountId}/transactions")
                            .queryParam("page", safePage)
                            .queryParam("size", safeSize)
                            .build(accountId))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            List<AccountTransactionDto> restList = OfsMessageUtil.parseTransactionEnquiryResponse(restResp);
            return restList != null ? restList : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to query CBS transactions for accountId={}: {}", accountId, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<AccountTransactionDto> getAccountTransactions(String accountId) {
        return getAccountTransactions(accountId, 0, 20);
    }

    public TransferInitiationResponse postToCbs(TransferInitiationRequest request, String txId) {
        return postToCbs(request, txId, false);
    }

    @Retry(name = "cbsService")
    @CircuitBreaker(name = "cbsService", fallbackMethod = "cbsPostingFallback")
    public TransferInitiationResponse postToCbs(TransferInitiationRequest request, String txId, boolean fundsHeld) {
        log.info("Sending funds transfer request to CBS via OFS: txId={}, amount={}, fundsHeld={}", txId, request.amount(), fundsHeld);

        String ofsPostingReq = OfsMessageUtil.buildFundsTransferInitiate(
                txId, request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), request.currency() != null ? request.currency() : "PHP", null
        );

        String ofsResponse = webClient.post()
                .uri("/api/v1/cbs/funds-transfer")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .bodyValue(ofsPostingReq)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        log.info("CBS transfer success response received for txId={}: {}", txId, ofsResponse);
        Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsResponse);
        if ("FAILURE".equalsIgnoreCase(fields.get("STATUS")) || "-1".equals(fields.get("STATUS_CODE"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Posting failed in CBS: " + fields.get("ERROR"));
        }

        return new TransferInitiationResponse(
                txId,
                TransactionStatus.Posted,
                request.amount(),
                request.currency() != null ? request.currency() : "PHP",
                request.sourceAccountId(),
                request.destinationAccountId(),
                "Transfer executed successfully on CBS core",
                false,
                0L,
                false,
                null,
                Instant.now()
        );
    }

    public TransferInitiationResponse cbsPostingFallback(TransferInitiationRequest request, String txId, Throwable t) {
        return cbsPostingFallback(request, txId, false, t);
    }

    public TransferInitiationResponse cbsPostingFallback(TransferInitiationRequest request, String txId, boolean fundsHeld, Throwable t) {
        log.error("CBS Circuit Breaker fallback activated for txId={}: {}", txId, t.getMessage());

        String incidentId = UUID.randomUUID().toString();
        TransferFailedToDlqEvent dlqEvent = TransferFailedToDlqEvent.builder()
                .incidentId(incidentId)
                .correlationId(txId)
                .transactionId(txId)
                .errorType("CBS_CIRCUIT_BREAKER_OR_TIMEOUT")
                .errorCode("CBS_DOWN_DLQ_ROUTED")
                .circuitBreakerState("OPEN")
                .sourceAccountId(request.sourceAccountId())
                .destinationAccountId(request.destinationAccountId())
                .amount(request.amount())
                .currency(request.currency() != null ? request.currency() : "PHP")
                .failureTimestampUtc(Instant.now())
                .stackTrace(t.getMessage())
                .originalPayload(Map.of(
                        "sourceAccountId", request.sourceAccountId(),
                        "destinationAccountId", request.destinationAccountId(),
                        "amount", request.amount()
                ))
                .build();

        try {
            kafkaTemplate.send(DLQ_TOPIC, txId, dlqEvent);
            log.info("Published failed transfer event to DLQ: incidentId={}, txId={}", incidentId, txId);
        } catch (Exception e) {
            log.error("Failed to publish DLQ event to Kafka: {}", e.getMessage(), e);
        }

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Core banking system is temporarily unavailable. Transaction queued into DLQ for safe replay: incident=" + incidentId,
                t
        );
    }

    public Map<String, String> getAccountBalance(String accountId) {
        log.info("Querying CBS balance for accountId={}", accountId);
        try {
            String ofsResponse = webClient.get()
                    .uri("/api/v1/cbs/accounts/{accountId}/balance", accountId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            return OfsMessageUtil.parseBalanceEnquiryResponse(ofsResponse);
        } catch (Exception e) {
            log.error("Failed to query CBS balance for accountId={}: {}", accountId, e.getMessage());
            return Map.of("accountId", accountId, "accountNumber", accountId, "currentBalance", "0.00", "availableBalance", "0.00");
        }
    }

    public Map<String, String> getSystemDate() {
        log.info("Querying CBS system date via OFS");
        try {
            String ofsResponse = webClient.get()
                    .uri("/api/v1/cbs/system-date")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            return OfsMessageUtil.parseOfsFields(ofsResponse);
        } catch (Exception e) {
            log.error("Failed to query CBS system date: {}", e.getMessage());
            return Map.of("STATUS", "ONLINE", "POSTING.WINDOW", "OPEN");
        }
    }

    public Map<String, String> triggerCob() {
        log.info("Triggering CBS COB batch run via OFS");
        try {
            String ofsResponse = webClient.post()
                    .uri("/api/v1/cbs/cob/run")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(10000))
                    .block();
            return OfsMessageUtil.parseOfsFields(ofsResponse);
        } catch (Exception e) {
            log.error("Failed to run CBS COB batch: {}", e.getMessage());
            return Map.of("STATUS", "FAILED", "ERROR", e.getMessage());
        }
    }
}
