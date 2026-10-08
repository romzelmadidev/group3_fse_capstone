package com.bank.orchestrator.service;

import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.dto.events.TransferFailedToDlqEvent;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import com.bank.orchestrator.dto.TransferInitiationRequest;
import com.bank.orchestrator.dto.TransferInitiationResponse;
import com.bank.ledger.contracts.enums.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
        log.info("Requesting CBS hold: accountId={}, amount={}, txId={}", accountId, amount, txId);
        Map<String, Object> payload = Map.of(
                "referenceId", txId,
                "accountId", accountId,
                "amount", amount,
                "currency", "PHP",
                "reason", "ANTI_SCAM_COOLING_OFF_HOLD"
        );
        try {
            webClient.post()
                    .uri("/api/v1/cbs/holds")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            log.info("Authoritative hold successfully placed in CBS for txId={}", txId);
        } catch (Exception e) {
            log.error("Failed to place authoritative CBS hold for txId={}: {}", txId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to reserve funds on core banking system: " + e.getMessage(), e);
        }
    }

    public void releaseHold(String accountId, BigDecimal amount, String txId) {
        log.info("Requesting CBS hold release: accountId={}, amount={}, txId={}", accountId, amount, txId);
        Map<String, Object> payload = Map.of(
                "referenceId", txId,
                "accountId", accountId,
                "amount", amount,
                "currency", "PHP",
                "reason", "COOLING_OFF_CANCELLED_OR_EXPIRED"
        );
        try {
            webClient.post()
                    .uri("/api/v1/cbs/holds/release")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            log.info("Authoritative hold successfully released in CBS for txId={}", txId);
        } catch (Exception e) {
            log.error("Failed to release CBS hold for txId={}: {}", txId, e.getMessage());
        }
    }

    public List<AccountTransactionDto> getAccountTransactions(String accountId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        log.info("Querying CBS past transactions for accountId={} (page={}, size={}) via OFS protocol", accountId, safePage, safeSize);
        try {
            String ofsEnquiry = OfsMessageUtil.buildTransactionEnquiry(accountId, safePage, safeSize);
            String ofsResponse = webClient.post()
                    .uri("/api/v1/cbs/ofs")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .bodyValue(ofsEnquiry)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();

            List<AccountTransactionDto> parsed = OfsMessageUtil.parseTransactionEnquiryResponse(ofsResponse);
            if (!parsed.isEmpty()) {
                log.info("Retrieved {} past transactions via OFS for accountId={}", parsed.size(), accountId);
                return parsed;
            }
        } catch (Exception e) {
            log.warn("OFS enquiry failed for accountId={}, falling back to REST endpoint: {}", accountId, e.getMessage());
        }

        // Option B REST Fallback
        try {
            List<AccountTransactionDto> restList = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/cbs/accounts/{accountId}/transactions")
                            .queryParam("page", safePage)
                            .queryParam("size", safeSize)
                            .build(accountId))
                    .retrieve()
                    .bodyToFlux(AccountTransactionDto.class)
                    .collectList()
                    .timeout(Duration.ofMillis(3000))
                    .block();
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

    @CircuitBreaker(name = "cbsService", fallbackMethod = "cbsPostingFallback")
    public TransferInitiationResponse postToCbs(TransferInitiationRequest request, String txId, boolean fundsHeld) {
        log.info("Sending funds transfer request to CBS: txId={}, amount={}, fundsHeld={}", txId, request.amount(), fundsHeld);

        Map<String, Object> payload = Map.of(
                "transactionId", txId,
                "sourceAccountId", request.sourceAccountId(),
                "destinationAccountId", request.destinationAccountId(),
                "amount", request.amount(),
                "currency", request.currency() != null ? request.currency() : "PHP",
                "description", request.description() != null ? request.description() : "Funds Transfer",
                "channel", "ORCHESTRATOR",
                "idempotencyKey", request.idempotencyKey() != null ? request.idempotencyKey() : txId,
                "fundsHeld", fundsHeld
        );

        Map<?, ?> response = webClient.post()
                .uri("/api/v1/cbs/postings/transfer")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        log.info("CBS transfer success response received for txId={}: {}", txId, response);

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
}
