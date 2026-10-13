package com.bank.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * Thrown when a transfer would breach a KYC-tier transfer cap (HTTP 402).
 * Extends ResponseStatusException so existing rethrow paths do not wrap it as a 500.
 */
public class LimitExceededException extends ResponseStatusException {

    private final String capKind;
    private final BigDecimal capValue;
    private final BigDecimal remaining;
    private final String tier;
    private final boolean restricted;

    public LimitExceededException(String capKind, BigDecimal capValue, BigDecimal remaining,
                                  String tier, boolean restricted) {
        super(HttpStatus.PAYMENT_REQUIRED, "Transfer limit exceeded: " + capKind);
        this.capKind = capKind;
        this.capValue = capValue;
        this.remaining = remaining;
        this.tier = tier;
        this.restricted = restricted;
    }

    public String getCapKind() {
        return capKind;
    }

    public BigDecimal getCapValue() {
        return capValue;
    }

    public BigDecimal getRemaining() {
        return remaining;
    }

    public String getTier() {
        return tier;
    }

    public boolean isRestricted() {
        return restricted;
    }
}
