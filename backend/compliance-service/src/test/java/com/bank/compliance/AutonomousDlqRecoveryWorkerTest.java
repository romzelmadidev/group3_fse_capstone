package com.bank.compliance;

import com.bank.compliance.scheduler.AutonomousDlqRecoveryWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutonomousDlqRecoveryWorkerTest {

    private AutonomousDlqRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AutonomousDlqRecoveryWorker(
                WebClient.builder(),
                new ObjectMapper(),
                "http://localhost:8085",
                "http://localhost:8082",
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("Worker identifies transient vs non-transient errors correctly")
    void testTransientErrorClassification() {
        assertTrue(worker.isTransientError("CBS_CIRCUIT_BREAKER_OR_TIMEOUT"));
        assertTrue(worker.isTransientError("HTTP 503 SERVICE_UNAVAILABLE"));
        assertTrue(worker.isTransientError("GATEWAY_TIMEOUT_504"));
        assertTrue(worker.isTransientError("CONNECTION_REFUSED"));
        assertTrue(worker.isTransientError("DATABASE_UNAVAILABLE_LIMIT_CHECK"));
        assertTrue(worker.isTransientError("CBS_DATABASE_UNAVAILABLE"));
        assertTrue(worker.isTransientError("LIMIT_SERVICE_DOWN"));

        assertFalse(worker.isTransientError("INSUFFICIENT_FUNDS"));
        assertFalse(worker.isTransientError("ACCOUNT_CLOSED"));
        assertFalse(worker.isTransientError("KYC_BLOCKED"));
        assertFalse(worker.isTransientError(null));
    }

    @Test
    @DisplayName("Daemon status reports configuration and micrometer counters")
    void testDaemonStatus() {
        Map<String, Object> status = worker.getDaemonStatus();
        assertNotNull(status);
        assertEquals(true, status.get("autonomousDaemonActive"));
        assertEquals(true, status.get("shedLockSynced"));
        assertEquals(10, status.get("maxAgeMinutes"));
        assertEquals(10, status.get("batchSize"));
        assertEquals(300L, status.get("throttleMs"));
        assertTrue(status.containsKey("metrics"));
    }
}
