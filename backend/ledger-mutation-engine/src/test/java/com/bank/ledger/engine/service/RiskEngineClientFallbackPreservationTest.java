package com.bank.ledger.engine.service;

import com.bank.ledger.contracts.dto.MutationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Preservation (3.5): with no device flags the fallback is still ALLOW / FALLBACK_STATIC_RULE. */
class RiskEngineClientFallbackPreservationTest {

    @Test
    void noDeviceFlagsStillFallsBackToAllow() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        RiskEngineClient client = new RiskEngineClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "riskServiceBaseUrl", "http://127.0.0.1:" + closedPort);

        MutationRequest request = MutationRequest.builder()
                .transactionId("TX-FALLBACK-PRESERVE")
                .accountId("acc-1")
                .targetAccountId("acc-2")
                .mutationAmount(new BigDecimal("500"))
                .build();

        RiskEngineClient.RiskEvaluationResult result = client.evaluateRisk(request);

        assertEquals("ALLOW", result.getDecision());
        assertEquals("FALLBACK_STATIC_RULE", result.getPrimaryFlag());
    }
}
