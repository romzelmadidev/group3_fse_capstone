package com.bank.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", HttpStatus.FORBIDDEN.value(),
                    "error", "TRANSACTION_DECLINED",
                    "code", "TX_DECLINED_POLICY",
                    "message", "Transaction could not be processed at this time. Please contact customer support.",
                    "timestampUtc", Instant.now().toString()
            ));
        }
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "status", ex.getStatusCode().value(),
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                "timestampUtc", Instant.now().toString()
        ));
    }

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleLimitExceeded(LimitExceededException ex) {
        String message;
        if (ex.isRestricted()) {
            message = "Your account is restricted, so transfers are unavailable. Please contact customer support.";
        } else {
            message = String.format(Locale.US,
                    "This transfer is above your %s limit of PHP %,.2f. You can send up to PHP %,.2f.",
                    capKindLabel(ex.getCapKind()), ex.getCapValue(), ex.getRemaining());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.PAYMENT_REQUIRED.value());
        body.put("error", "LIMIT_EXCEEDED");
        body.put("code", "TX_LIMIT_EXCEEDED");
        body.put("message", message);
        body.put("capKind", ex.getCapKind());
        body.put("capValue", ex.getCapValue());
        body.put("remaining", ex.getRemaining());
        body.put("tier", ex.getTier());
        body.put("restricted", ex.isRestricted());
        if ("BASIC".equals(ex.getTier()) && !ex.isRestricted()) {
            body.put("upgradeGuidance", "Complete KYC verification to raise your limits.");
        }
        body.put("timestampUtc", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }

    @ExceptionHandler(LimitCheckUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleLimitCheckUnavailable(LimitCheckUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "LIMIT_CHECK_UNAVAILABLE",
                "code", "TX_LIMIT_UNVERIFIED",
                "message", "We couldn't verify your transfer limits. Please try again shortly.",
                "timestampUtc", Instant.now().toString()
        ));
    }

    private static String capKindLabel(String capKind) {
        if (capKind == null) {
            return "transfer";
        }
        return switch (capKind) {
            case "PER_TRANSFER" -> "per-transfer";
            case "DAILY" -> "daily";
            case "MONTHLY" -> "monthly";
            default -> capKind.toLowerCase(Locale.ROOT);
        };
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<Map<String, Object>> handleWebClientResponseException(WebClientResponseException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "status", ex.getStatusCode().value(),
                "error", ex.getStatusCode().toString(),
                "message", ex.getResponseBodyAsString().isBlank() ? ex.getMessage() : ex.getResponseBodyAsString(),
                "timestampUtc", Instant.now().toString()
        ));
    }
}
