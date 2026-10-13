package com.bank.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown when the Limit_Service cannot be reached or returns an unusable answer (HTTP 503, fail closed).
 */
public class LimitCheckUnavailableException extends ResponseStatusException {

    private final String incidentId;

    public LimitCheckUnavailableException(String reason, Throwable cause) {
        this(reason, null, cause);
    }

    public LimitCheckUnavailableException(String reason, String incidentId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
        this.incidentId = incidentId;
    }

    public String getIncidentId() {
        return incidentId;
    }
}
