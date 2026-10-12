package com.bank.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Standardized Bilateral Inter-Bank Wire Transfer Request Payload.
 * Supports both nested PartyDetails (official partner spec)
 * and flat fields (legacy / internal compatibility).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterbankWireRequest(
        String messageId,
        String referenceId,
        String sourceBankCode,
        String originatingBank,
        String destinationBankCode,
        PartyDetails sender,
        PartyDetails beneficiary,
        Object amount,
        String currency,
        String purpose,
        String senderReference,
        String createdAt,
        String beneficiaryAccountId,
        String beneficiaryName,
        String senderAccountId,
        String senderName
) {
    public record PartyDetails(
            String accountNumber,
            String name
    ) {}

    // Constructor matching official partner nested format
    public InterbankWireRequest(
            String messageId,
            String sourceBankCode,
            String destinationBankCode,
            PartyDetails sender,
            PartyDetails beneficiary,
            Object amount,
            String currency,
            String purpose,
            String senderReference,
            String createdAt
    ) {
        this(
                messageId,
                null,
                sourceBankCode,
                null,
                destinationBankCode,
                sender,
                beneficiary,
                amount,
                currency,
                purpose,
                senderReference,
                createdAt,
                beneficiary != null ? beneficiary.accountNumber() : null,
                beneficiary != null ? beneficiary.name() : null,
                sender != null ? sender.accountNumber() : null,
                sender != null ? sender.name() : null
        );
    }

    // Convenience constructor for testing and internal service mapping
    public InterbankWireRequest(
            String messageId,
            String sourceBankCode,
            String destinationBankCode,
            String senderAccount,
            String senderName,
            String beneficiaryAccount,
            String beneficiaryName,
            BigDecimal amountVal,
            String currency,
            String purpose,
            String senderReference,
            String createdAt
    ) {
        this(
                messageId,
                null,
                sourceBankCode,
                null,
                destinationBankCode,
                new PartyDetails(senderAccount, senderName),
                new PartyDetails(beneficiaryAccount, beneficiaryName),
                amountVal != null ? amountVal.setScale(2, RoundingMode.HALF_UP).toPlainString() : "0.00",
                currency,
                purpose,
                senderReference,
                createdAt,
                beneficiaryAccount,
                beneficiaryName,
                senderAccount,
                senderName
        );
    }

    public BigDecimal getAmountAsBigDecimal() {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        if (amount instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        String str = amount.toString().trim();
        if (str.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(str);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public String getEffectiveMessageId() {
        if (messageId != null && !messageId.isBlank()) return messageId;
        if (referenceId != null && !referenceId.isBlank()) return referenceId;
        return null;
    }

    public String getEffectiveSourceBank() {
        if (sourceBankCode != null && !sourceBankCode.isBlank()) return sourceBankCode;
        if (originatingBank != null && !originatingBank.isBlank()) return originatingBank;
        return "COOBS";
    }

    public String getBeneficiaryAccountNumber() {
        if (beneficiary != null && beneficiary.accountNumber() != null && !beneficiary.accountNumber().isBlank()) {
            return beneficiary.accountNumber();
        }
        return beneficiaryAccountId;
    }

    public String getBeneficiaryName() {
        if (beneficiary != null && beneficiary.name() != null && !beneficiary.name().isBlank()) {
            return beneficiary.name();
        }
        return beneficiaryName;
    }

    public String getSenderAccountNumber() {
        if (sender != null && sender.accountNumber() != null && !sender.accountNumber().isBlank()) {
            return sender.accountNumber();
        }
        return senderAccountId;
    }

    public String getSenderName() {
        if (sender != null && sender.name() != null && !sender.name().isBlank()) {
            return sender.name();
        }
        return senderName;
    }
}
