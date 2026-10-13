package com.bank.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown when the Limit_Service cannot be reached or returns an unusable answer (HTTP 503, fail closed).
 */
public class LimitCheckUnavailableException extends ResponseStatusException {

    public LimitCheckUnavailableException(String reason, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    }
}
