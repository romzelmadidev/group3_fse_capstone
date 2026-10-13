package com.bank.orchestrator.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Feature: kyc-tiered-transfer-limits, Property 7: Error shape and upgrade guidance.
 * Validates: Requirements 5.1, 5.2, 5.3, 5.4.
 */
class GlobalExceptionHandlerTest {

    private static final List<String> CAP_KINDS = List.of("PER_TRANSFER", "DAILY", "MONTHLY");
    private static final List<String> TIERS = List.of("BASIC", "FULLY_VERIFIED");
    private static final int ITERATIONS = 200;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // Feature: kyc-tiered-transfer-limits, Property 7: Error shape and upgrade guidance
    @Test
    void limitExceededResponseHasStableShapeAndGuidanceIffBasicAndNotRestricted() {
        Random rnd = new Random(0);
        for (int i = 0; i < ITERATIONS; i++) {
            String capKind = CAP_KINDS.get(rnd.nextInt(CAP_KINDS.size()));
            BigDecimal capValue = BigDecimal.valueOf(rnd.nextInt(100_000_000), 2);
            BigDecimal remaining = BigDecimal.valueOf(rnd.nextInt(100_000_000), 2);
            String tier = TIERS.get(rnd.nextInt(TIERS.size()));
            boolean restricted = rnd.nextBoolean();

            ResponseEntity<Map<String, Object>> response = handler.handleLimitExceeded(
                    new LimitExceededException(capKind, capValue, remaining, tier, restricted));
            Map<String, Object> body = response.getBody();
            String ctx = "iteration " + i + " " + capKind + "/" + tier + "/restricted=" + restricted;

            assertEquals(HttpStatus.PAYMENT_REQUIRED, response.getStatusCode(), ctx);
            assertNotNull(body, ctx);
            assertEquals(402, body.get("status"), ctx);
            assertEquals("LIMIT_EXCEEDED", body.get("error"), ctx);
            assertEquals("TX_LIMIT_EXCEEDED", body.get("code"), ctx);
            assertEquals(capKind, body.get("capKind"), ctx);
            assertEquals(capValue, body.get("capValue"), ctx);
            assertEquals(remaining, body.get("remaining"), ctx);
            assertEquals(tier, body.get("tier"), ctx);
            assertEquals(restricted, body.get("restricted"), ctx);
            assertTrue(body.get("message") instanceof String m && !m.isBlank(), ctx);
            assertTrue(body.get("timestampUtc") instanceof String t && !t.isBlank(), ctx);

            boolean expectGuidance = "BASIC".equals(tier) && !restricted;
            assertEquals(expectGuidance, body.containsKey("upgradeGuidance"), ctx);
            if (expectGuidance) {
                assertTrue(((String) body.get("upgradeGuidance")).contains("KYC"), ctx);
            }

            String message = (String) body.get("message");
            if (restricted) {
                assertTrue(message.toLowerCase().contains("restricted"), ctx);
                assertFalse(body.containsKey("upgradeGuidance"), ctx);
            } else {
                assertFalse(message.toLowerCase().contains("restricted"), ctx);
            }

            // Requirement 5.4: distinct from insufficient balance / risk hold statuses
            int status = response.getStatusCode().value();
            assertNotEquals(400, status, ctx);
            assertNotEquals(403, status, ctx);
            assertNotEquals(422, status, ctx);
        }
    }

    // Feature: kyc-tiered-transfer-limits, Property 7: Error shape and upgrade guidance
    @Test
    void limitCheckUnavailableReturns503WithFixedBody() {
        ResponseEntity<Map<String, Object>> response = handler.handleLimitCheckUnavailable(
                new LimitCheckUnavailableException("anything, must not leak", new RuntimeException("boom")));
        Map<String, Object> body = response.getBody();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(body);
        assertEquals(503, body.get("status"));
        assertEquals("LIMIT_CHECK_UNAVAILABLE", body.get("error"));
        assertEquals("TX_LIMIT_UNVERIFIED", body.get("code"));
        assertEquals("We couldn't verify your transfer limits. Please try again shortly.", body.get("message"));
        assertNotNull(body.get("timestampUtc"));
    }

    // Feature: kyc-tiered-transfer-limits, Property 7: Error shape and upgrade guidance
    @Test
    void dedicatedHandlersAreChosenOverGenericResponseStatusExceptionHandler() throws Exception {
        ThrowingController controller = new ThrowingController();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();
        ObjectMapper mapper = new ObjectMapper();
        Random rnd = new Random(0);

        for (int i = 0; i < 100; i++) {
            String tier = TIERS.get(rnd.nextInt(TIERS.size()));
            boolean restricted = rnd.nextBoolean();
            controller.toThrow = new LimitExceededException(
                    CAP_KINDS.get(rnd.nextInt(CAP_KINDS.size())),
                    BigDecimal.valueOf(rnd.nextInt(1_000_000), 2),
                    BigDecimal.valueOf(rnd.nextInt(1_000_000), 2), tier, restricted);

            var mvcResponse = mvc.perform(get("/throw")).andReturn().getResponse();
            JsonNode node = mapper.readTree(mvcResponse.getContentAsString());

            assertEquals(402, mvcResponse.getStatus());
            assertEquals("LIMIT_EXCEEDED", node.get("error").asText());
            assertEquals("TX_LIMIT_EXCEEDED", node.get("code").asText());
            assertEquals("BASIC".equals(tier) && !restricted, node.has("upgradeGuidance"));
        }

        controller.toThrow = new LimitCheckUnavailableException("down", null);
        var unavailable = mvc.perform(get("/throw")).andReturn().getResponse();
        JsonNode unavailableBody = mapper.readTree(unavailable.getContentAsString());
        assertEquals(503, unavailable.getStatus());
        assertEquals("LIMIT_CHECK_UNAVAILABLE", unavailableBody.get("error").asText());
        assertEquals("TX_LIMIT_UNVERIFIED", unavailableBody.get("code").asText());

        // A plain ResponseStatusException still goes through the generic handler.
        controller.toThrow = new ResponseStatusException(HttpStatus.FORBIDDEN, "nope");
        var forbidden = mvc.perform(get("/throw")).andReturn().getResponse();
        JsonNode forbiddenBody = mapper.readTree(forbidden.getContentAsString());
        assertEquals(403, forbidden.getStatus());
        assertEquals("TRANSACTION_DECLINED", forbiddenBody.get("error").asText());
    }

    @RestController
    static class ThrowingController {
        volatile ResponseStatusException toThrow;

        @GetMapping("/throw")
        String boom() {
            throw toThrow;
        }
    }
}
