package com.bank.ledger.contracts.enums;

/**
 * Standardized State Transition Reason Codes.
 */
public final class ChangeReasonCode {
    private ChangeReasonCode() {}

    public static final String API_INGESTION = "API_INGESTION";
    public static final String BIOMETRIC_AUTH_VERIFIED = "BIOMETRIC_AUTH_VERIFIED";
    public static final String SCAM_ADVISORY_CONFIRMED_BIOMETRIC_VERIFIED = "SCAM_ADVISORY_CONFIRMED_BIOMETRIC_VERIFIED";
    public static final String FRAUD_POLICY_CIRCUIT_CUT = "FRAUD_POLICY_CIRCUIT_CUT";
    public static final String BIOMETRIC_ATTEMPTS_EXCEEDED = "BIOMETRIC_ATTEMPTS_EXCEEDED";
    public static final String USER_CANCELLED_BIOMETRIC = "USER_CANCELLED_BIOMETRIC";
    public static final String USER_COOL_OFF_CANCELLED = "USER_COOL_OFF_CANCELLED";
    public static final String USER_ADVISORY_ABORTED = "USER_ADVISORY_ABORTED";
    public static final String FUNDS_RESERVATION_EARMARKED = "FUNDS_RESERVATION_EARMARKED";
    public static final String CBS_OFS_PROCESSING = "CBS_OFS_PROCESSING";
    public static final String CIRCUIT_BREAKER_TRIPPED_DLQ = "CIRCUIT_BREAKER_TRIPPED_DLQ";
    public static final String ACID_LEDGER_COMMITTED = "ACID_LEDGER_COMMITTED";
    public static final String CBS_SOLVENCY_DEFICIT = "CBS_SOLVENCY_DEFICIT";
    public static final String CBS_ACCOUNT_INACTIVE = "CBS_ACCOUNT_INACTIVE";
    public static final String MAKER_DISPUTE_FILED = "MAKER_DISPUTE_FILED";
    public static final String CHECKER_REVERSAL_APPROVED_SETTLED = "CHECKER_REVERSAL_APPROVED_SETTLED";
    public static final String CHECKER_REVERSAL_REJECTED = "CHECKER_REVERSAL_REJECTED";
}
