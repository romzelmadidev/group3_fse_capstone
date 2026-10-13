package com.bank.orchestrator.service;

import com.bank.orchestrator.exception.LimitCheckUnavailableException;
import com.bank.orchestrator.exception.LimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Client for the account-service Limit_Service. Fails closed: any timeout, non-200 status
 * or unusable body on evaluate becomes {@link LimitCheckUnavailableException}.
 */
@Service
public class LimitClientService {

    private static final Logger log = LoggerFactory.getLogger(LimitClientService.class);
    private static final Duration TIMEOUT = Duration.ofMillis(2000);

    /** Result of an allowed evaluation. The customer lock stays held until {@code release(lockToken)}. */
    public record LimitEvaluation(String lockToken) {}

    public record EvaluateRequest(String transactionId, String sourceAccountId, BigDecimal amount) {}

    public record ReleaseRequest(String lockToken) {}

    public record Breach(String capKind, BigDecimal capValue, BigDecimal remaining, String tier, boolean restricted) {}

    public record EvaluateResponse(boolean allowed, String lockToken, Breach breach) {}

    private final WebClient webClient;

    public LimitClientService(
            WebClient.Builder webClientBuilder,
            @Value("${services.account.url:http://localhost:8081}") String accountServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(accountServiceUrl).build();
    }

    public LimitEvaluation evaluate(String transactionId, String sourceAccountId, BigDecimal amount) {
        EvaluateResponse body;
        try {
            ResponseEntity<EvaluateResponse> response = webClient.post()
                    .uri("/internal/limits/evaluate")
                    .bodyValue(new EvaluateRequest(transactionId, sourceAccountId, amount))
                    .retrieve()
                    .toEntity(EvaluateResponse.class)
                    .timeout(TIMEOUT)
                    .block();
            if (response == null || response.getStatusCode() != HttpStatus.OK) {
                throw new IllegalStateException("Unexpected response status: "
                        + (response == null ? "none" : response.getStatusCode()));
            }
            body = response.getBody();
        } catch (Exception e) {
            log.warn("Limit service unavailable for {}: {}", transactionId, e.getMessage());
            throw new LimitCheckUnavailableException("Transfer limits could not be verified", e);
        }

        if (body == null) {
            throw new LimitCheckUnavailableException("Limit service returned an empty body", null);
        }
        if (body.allowed()) {
            if (body.lockToken() == null || body.lockToken().isBlank()) {
                throw new LimitCheckUnavailableException("Limit service allowed without a lock token", null);
            }
            return new LimitEvaluation(body.lockToken());
        }
        Breach breach = body.breach();
        if (breach == null || breach.capKind() == null) {
            throw new LimitCheckUnavailableException("Limit service rejected without breach details", null);
        }
        throw new LimitExceededException(breach.capKind(), breach.capValue(), breach.remaining(),
                breach.tier(), breach.restricted());
    }

    /** Releases the customer lock. Best effort: the lock expires on its own if this fails. */
    public void release(String lockToken) {
        try {
            webClient.post()
                    .uri("/internal/limits/release")
                    .bodyValue(new ReleaseRequest(lockToken))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(TIMEOUT)
                    .block();
        } catch (Exception e) {
            log.warn("Failed to release limit lock; it will expire on its own: {}", e.getMessage());
        }
    }
}
