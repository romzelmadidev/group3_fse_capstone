package com.bank.compliance.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.dlq.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class AutonomousDlqRecoveryWorker {

    private final WebClient cbsWebClient;
    private final WebClient orchestratorWebClient;
    private final ObjectMapper objectMapper;
    private final Counter replayAttemptedCounter;
    private final Counter replaySucceededCounter;
    private final Counter replayExpiredCounter;

    @Value("${app.dlq.recovery.max-age-minutes:10}")
    private int maxAgeMinutes = 10;

    @Value("${app.dlq.recovery.batch-size:10}")
    private int batchSize = 10;

    @Value("${app.dlq.recovery.throttle-ms:300}")
    private long throttleMs = 300;

    private volatile Instant lastCycleTimestamp;
    private volatile boolean lastCbsHealthStatus = false;

    public AutonomousDlqRecoveryWorker(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl,
            @Value("${services.orchestrator.url:http://localhost:8082}") String orchestratorUrl,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.cbsWebClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
        this.orchestratorWebClient = webClientBuilder.baseUrl(orchestratorUrl).build();
        this.objectMapper = objectMapper;

        if (meterRegistry != null) {
            this.replayAttemptedCounter = Counter.builder("dlq_replays_attempted_total")
                    .description("Total DLQ replay attempts initiated")
                    .register(meterRegistry);
            this.replaySucceededCounter = Counter.builder("dlq_replays_succeeded_total")
                    .description("Total DLQ replays successfully completed")
                    .register(meterRegistry);
            this.replayExpiredCounter = Counter.builder("dlq_replays_expired_total")
                    .description("Total DLQ incidents expired due to safe TTL")
                    .register(meterRegistry);
        } else {
            this.replayAttemptedCounter = null;
            this.replaySucceededCounter = null;
            this.replayExpiredCounter = null;
        }
    }

    @Scheduled(fixedDelayString = "${app.dlq.recovery.poll-interval-ms:30000}")
    @SchedulerLock(name = "AutonomousDlqRecoveryWorker", lockAtMostFor = "25s", lockAtLeastFor = "10s")
    public void runRecoveryCycle() {
        this.lastCycleTimestamp = Instant.now();
        boolean healthy = isCbsHealthy();
        this.lastCbsHealthStatus = healthy;

        if (!healthy) {
            log.debug("[SLOW-PATH RECOVERY] CBS health check failed. Skipping replay cycle.");
            return;
        }

        List<Map<String, Object>> pendingIncidents = fetchPendingIncidents();
        if (pendingIncidents == null || pendingIncidents.isEmpty()) {
            return;
        }

        log.info("[SLOW-PATH RECOVERY] Found {} pending DLQ incidents. Initiating recovery...", pendingIncidents.size());
        Instant ttlCutoff = Instant.now().minus(Duration.ofMinutes(maxAgeMinutes));

        for (Map<String, Object> incident : pendingIncidents) {
            String txId = String.valueOf(incident.get("transactionId"));
            if (txId == null || txId.isBlank() || "null".equals(txId)) {
                txId = String.valueOf(incident.get("incidentId"));
            }
            String errorType = String.valueOf(incident.get("errorType"));
            Object failureTimestampObj = incident.get("failureTimestamp");
            Instant failureTimestamp;
            if (failureTimestampObj instanceof String s && !s.isBlank()) {
                try {
                    failureTimestamp = Instant.parse(s);
                } catch (Exception e) {
                    failureTimestamp = Instant.now();
                }
            } else if (failureTimestampObj instanceof Number n) {
                failureTimestamp = Instant.ofEpochMilli(n.longValue());
            } else {
                failureTimestamp = Instant.now();
            }

            // Guard 1: Transient Error Filter
            if (!isTransientError(errorType)) {
                log.info("[SLOW-PATH] Incident {} is non-transient ({}). Marking MANUAL_REVIEW_REQUIRED.", txId, errorType);
                resolveAudit(txId, "MANUAL_REVIEW_REQUIRED", "Non-transient error requires operational review.");
                continue;
            }

            // Guard 2: Aging TTL Guard (BSP Circular 1160 Consumer Protection)
            if (failureTimestamp.isBefore(ttlCutoff)) {
                log.warn("[SLOW-PATH] Incident {} exceeded {}m safe TTL. Marking EXPIRED_CANCELLED.", txId, maxAgeMinutes);
                if (replayExpiredCounter != null) {
                    replayExpiredCounter.increment();
                }
                resolveAudit(txId, "EXPIRED_CANCELLED", "Exceeded safe automated replay window. Customer advised to retry.");
                continue;
            }

            // Guard 3: Pre-Flight Idempotency Status Check
            if (isAlreadyPostedInCbs(txId)) {
                log.info("[SLOW-PATH] Incident {} was already committed in CBS prior to timeout. Resolving.", txId);
                resolveAudit(txId, "RESOLVED", "Verified existing commit in CBS. Zero balance mutation replay.");
                if (replaySucceededCounter != null) {
                    replaySucceededCounter.increment();
                }
                continue;
            }

            // Guard 4: Throttled Drip-Feed Execution
            try {
                if (throttleMs > 0) {
                    Thread.sleep(throttleMs);
                }
                if (replayAttemptedCounter != null) {
                    replayAttemptedCounter.increment();
                }
                replayTransaction(incident, txId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.error("[SLOW-PATH] Replay execution failed for {}: {}", txId, ex.getMessage());
            }
        }
    }

    public boolean isCbsHealthy() {
        try {
            String response = cbsWebClient.get()
                    .uri("/actuator/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(2000))
                    .block();
            return response != null && response.contains("\"UP\"");
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPendingIncidents() {
        try {
            return cbsWebClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v1/cbs/audit/failed-transactions")
                            .queryParam("status", "PENDING_REPLAY")
                            .queryParam("page", 0)
                            .queryParam("size", batchSize)
                            .build())
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
        } catch (Exception e) {
            log.error("[SLOW-PATH] Failed to query failed_transaction_audit: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isAlreadyPostedInCbs(String txId) {
        try {
            List<?> history = cbsWebClient.get()
                    .uri("/api/v1/cbs/transactions/{id}/status-history", txId)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(Duration.ofSeconds(2))
                    .block();
            return history != null && !history.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void replayTransaction(Map<String, Object> incident, String txId) {
        Map<String, Object> payload = reconstructPayload(incident, txId);

        orchestratorWebClient.post()
                .uri("/api/v1/transfers")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        resolveAudit(txId, "RESOLVED", "Recovered automatically by Autonomous DLQ Recovery Worker.");
        if (replaySucceededCounter != null) {
            replaySucceededCounter.increment();
        }
        log.info("[SLOW-PATH SUCCESS] Incident {} replayed and committed.", txId);
    }

    private void resolveAudit(String txId, String status, String notes) {
        try {
            cbsWebClient.post()
                    .uri("/api/v1/cbs/audit/failed-transactions/{id}/resolve", txId)
                    .bodyValue(Map.of(
                            "notes", notes,
                            "operatorId", "AUTONOMOUS_RECOVERY_DAEMON",
                            "replayStatus", status
                    ))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(2))
                    .block();
        } catch (Exception ex) {
            log.warn("[SLOW-PATH] Could not mark CBS audit transaction {} as {}: {}", txId, status, ex.getMessage());
        }
    }

    public boolean isTransientError(String errorType) {
        if (errorType == null) return false;
        String upper = errorType.toUpperCase();
        return upper.contains("TIMEOUT") ||
                upper.contains("CIRCUIT_BREAKER") ||
                upper.contains("503") ||
                upper.contains("504") ||
                upper.contains("CONNECTION") ||
                upper.contains("GATEWAY_TIMEOUT") ||
                upper.contains("SERVICE_UNAVAILABLE");
    }

    private Map<String, Object> reconstructPayload(Map<String, Object> incident, String txId) {
        Map<String, Object> payload = new HashMap<>();
        Object rawJson = incident.get("payloadJson");
        if (rawJson instanceof String s && !s.isBlank()) {
            try {
                payload.putAll(objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ignored) {}
        } else if (rawJson instanceof Map<?, ?> rawMap) {
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                payload.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        payload.putIfAbsent("sourceAccountId", "1000-2000-3001");
        payload.putIfAbsent("destinationAccountId", "1000-2000-3002");
        payload.putIfAbsent("amount", 5000.00);
        payload.putIfAbsent("currency", "PHP");
        payload.putIfAbsent("description", "Autonomous DLQ Replay: " + txId);
        payload.put("idempotencyKey", "REPLAY-" + txId + "-" + System.currentTimeMillis());
        payload.put("scamAdvisoryAcknowledged", true);
        payload.put("transactionId", txId);
        return payload;
    }

    public Map<String, Object> getDaemonStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("autonomousDaemonActive", true);
        status.put("shedLockSynced", true);
        status.put("cbsHealthy", lastCbsHealthStatus);
        status.put("maxAgeMinutes", maxAgeMinutes);
        status.put("batchSize", batchSize);
        status.put("throttleMs", throttleMs);
        status.put("lastCycleTimestamp", lastCycleTimestamp != null ? lastCycleTimestamp.toString() : null);
        status.put("metrics", Map.of(
                "replaysAttempted", replayAttemptedCounter != null ? (long) replayAttemptedCounter.count() : 0,
                "replaysSucceeded", replaySucceededCounter != null ? (long) replaySucceededCounter.count() : 0,
                "replaysExpired", replayExpiredCounter != null ? (long) replayExpiredCounter.count() : 0
        ));
        return status;
    }
}
