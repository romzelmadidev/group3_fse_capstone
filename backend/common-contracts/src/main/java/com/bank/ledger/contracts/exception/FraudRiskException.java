package com.bank.ledger.contracts.exception;

public class FraudRiskException extends RuntimeException {

    private final double riskScore;
    private final String reason;

    public FraudRiskException(String message, double riskScore, String reason) {
        super(message);
        this.riskScore = riskScore;
        this.reason = reason;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public String getReason() {
        return reason;
    }
}
