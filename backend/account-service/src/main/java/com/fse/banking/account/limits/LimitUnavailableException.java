package com.fse.banking.account.limits;

/**
 * Limits could not be verified (CBS usage failure, lock timeout, unknown account or user).
 * Callers must fail closed: reject the transfer, or answer 503 on the read endpoint.
 */
public class LimitUnavailableException extends RuntimeException {

    public LimitUnavailableException(String message) {
        super(message);
    }

    public LimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
