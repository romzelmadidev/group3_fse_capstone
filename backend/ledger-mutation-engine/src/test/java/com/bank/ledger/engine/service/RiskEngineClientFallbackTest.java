package com.bank.ledger.engine.service;

import com.bank.ledger.contracts.dto.MutationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bug condition exploration (defect 1.6). Asserts the EXPECTED fixed behaviour, so it
 * fails on unfixed code where the fallback is always ALLOW / FALLBACK_STATIC_RULE.
 */
class RiskEngineClientFallbackTest {

    private static RiskEngineClient clientPointedAtClosedPort() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        RiskEngineClient client = new RiskEngineClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "riskServiceBaseUrl", "http://127.0.0.1:" + closedPort);
        return client;
    }

    @Test
    void rootedRequestFallsBackToRequire2faWhenRiskServiceUnreachable() throws IOException {
        MutationRequest request = MutationRequest.builder()
                .transactionId("TX-FALLBACK-1")
                .accountId("acc-1")
                .targetAccountId("acc-2")
                .mutationAmount(new BigDecimal("500"))
                .rooted(true)
                .build();

        RiskEngineClient.RiskEvaluationResult result = clientPointedAtClosedPort().evaluateRisk(request);

        assertEquals("REQUIRE_2FA", result.getDecision());
        assertEquals(40, result.getFraudScore());
        assertEquals("RISK_SERVICE_UNAVAILABLE", result.getPrimaryFlag());
    }
}
