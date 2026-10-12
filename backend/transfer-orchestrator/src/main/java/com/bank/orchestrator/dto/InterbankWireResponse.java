package com.bank.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Standardized Bilateral Inter-Bank Wire Transfer Response Payload.
 * Format agreed with Partner Bank Group:
 *
 * Successful Credit:
 * {
 *   "messageId": "0b6c2f6e-6d1a-4d5c-9d1f-0f3a5e7b9c21",
 *   "status": "ACCEPTED",
 *   "receiverReference": "IB-20261009-548192",
 *   "beneficiaryName": "J*** D*** C***",
 *   "creditedAt": "2026-10-09T04:30:01.123Z"
 * }
 *
 * Rejected by business rules:
 * {
 *   "messageId": "0b6c2f6e-6d1a-4d5c-9d1f-0f3a5e7b9c21",
 *   "status": "REJECTED",
 *   "reasonCode": "ACCOUNT_NOT_FOUND",
 *   "reasonMessage": "No account with that number.",
 *   "rejectedAt": "2026-10-09T04:30:01.456Z"
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterbankWireResponse(
        String messageId,
        String status,
        String receiverReference,
        String beneficiaryName,
        String creditedAt,
        String reasonCode,
        String reasonMessage,
        String rejectedAt
) {
    public static InterbankWireResponse accepted(String messageId, String receiverReference, String beneficiaryName) {
        return new InterbankWireResponse(
                messageId,
                "ACCEPTED",
                receiverReference,
                beneficiaryName,
                Instant.now().toString(),
                null,
                null,
                null
        );
    }

    public static InterbankWireResponse rejected(String messageId, String reasonCode, String reasonMessage) {
        return new InterbankWireResponse(
                messageId,
                "REJECTED",
                null,
                null,
                null,
                reasonCode,
                reasonMessage,
                Instant.now().toString()
        );
    }

    /**
     * Masks person name per banking PII privacy guidelines:
     * e.g. "Juan Dela Cruz" -> "J*** D*** C***"
     */
    public static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "U***";
        }
        String[] parts = fullName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(parts[i].charAt(0)).append("***");
                if (i < parts.length - 1) {
                    sb.append(" ");
                }
            }
        }
        return sb.toString();
    }

    // Backward-compatibility accessors
    public String partnerReference() {
        return receiverReference;
    }

    public String message() {
        return reasonMessage != null ? reasonMessage : (status + (receiverReference != null ? " (" + receiverReference + ")" : ""));
    }
}
