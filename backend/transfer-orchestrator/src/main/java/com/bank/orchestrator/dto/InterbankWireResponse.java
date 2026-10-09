package com.bank.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standardized Bilateral Inter-Bank Wire Transfer Response Payload.
 * Agreed wire format between AuraBank (Group 3) and Partner Bank (Group B).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterbankWireResponse(
        String status,
        String partnerReference,
        String timestamp,
        String message
) {
    public InterbankWireResponse(String status, String partnerReference, String timestamp) {
        this(status, partnerReference, timestamp, null);
    }
}
