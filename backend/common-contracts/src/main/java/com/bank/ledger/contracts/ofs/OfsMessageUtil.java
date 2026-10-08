package com.bank.ledger.contracts.ofs;

import com.bank.ledger.contracts.dto.AccountTransactionDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for serializing and parsing Temenos Open Financial Services (OFS) protocol strings.
 */
public final class OfsMessageUtil {

    private OfsMessageUtil() {}

    /**
     * Builds an OFS message string for funds transfer initiation.
     */
    public static String buildFundsTransferInitiate(String transactionId, String sourceAccountId,
                                                    String destinationAccountId, BigDecimal amount,
                                                    String currency, String valueDate) {
        String cleanDate = (valueDate != null) ? valueDate.replace("-", "") : "20261007";
        return String.format(
                "FUNDS.TRANSFER,INITIATE/I/PROCESS//%s,USER01/%s,TRANSACTION.TYPE=AC,DEBIT.ACCT.NO=%s,CREDIT.ACCT.NO=%s,AMOUNT=%s,CURRENCY=%s,VALUE.DATE=%s",
                transactionId, transactionId, sourceAccountId, destinationAccountId, amount.toPlainString(), currency, cleanDate
        );
    }

    /**
     * Builds an OFS message string for funds transfer reversal.
     */
    public static String buildFundsTransferReversal(String originalCbsReference, String ticketId, String checkerId) {
        String checker = (checkerId != null && !checkerId.isBlank()) ? checkerId : "MGR02";
        return String.format(
                "FUNDS.TRANSFER,REVERSAL/I/PROCESS//%s,%s/123456,ORIGINAL.FT.NO=%s,TICKET.ID=%s",
                ticketId, checker, originalCbsReference, ticketId
        );
    }

    /**
     * Builds an OFS balance enquiry string.
     */
    public static String buildBalanceEnquiry(String accountId) {
        return String.format("ENQUIRY.SELECT,,USER01/123456,ACCOUNT.NUMBER:EQ=%s", accountId);
    }

    /**
     * Builds an OFS transaction enquiry string with pagination.
     */
    public static String buildTransactionEnquiry(String accountId, int page, int size) {
        return String.format("ENQUIRY.SELECT,,USER01/123456,OPERATION=TRANSACTION.LIST,ACCOUNT.NUMBER:EQ=%s,PAGE:EQ=%d,SIZE:EQ=%d",
                accountId, Math.max(0, page), Math.max(1, size));
    }

    /**
     * Builds an OFS transaction enquiry string with default pagination (page=0, size=20).
     */
    public static String buildTransactionEnquiry(String accountId) {
        return buildTransactionEnquiry(accountId, 0, 20);
    }

    /**
     * Parses key-value pairs from an OFS payload segment.
     */
    public static Map<String, String> parseOfsFields(String ofsMessage) {
        Map<String, String> fields = new HashMap<>();
        if (ofsMessage == null || ofsMessage.isBlank()) {
            return fields;
        }

        String[] parts = ofsMessage.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int colonEqIdx = part.indexOf(":EQ=");
            if (colonEqIdx > 0) {
                String key = part.substring(0, colonEqIdx).trim();
                String val = part.substring(colonEqIdx + 4).trim();
                fields.put(key, val);
            } else {
                int eqIdx = part.indexOf('=');
                if (eqIdx > 0) {
                    String key = part.substring(0, eqIdx).trim();
                    String val = part.substring(eqIdx + 1).trim();
                    fields.put(key, val);
                } else if (i == 0 && !part.isBlank() && !fields.containsKey("OPERATION")) {
                    fields.put("OPERATION", part.trim());
                }
            }
        }
        return fields;
    }

    /**
     * Builds an OFS response string.
     */
    public static String buildOfsResponse(boolean success, String reference, String message) {
        if (success) {
            return String.format("//1,SUCCESS,TXN.ID=%s,MESSAGE=%s", reference, message);
        } else {
            return String.format("//-1,FAILURE,ERROR=%s,MESSAGE=%s", reference, message);
        }
    }

    /**
     * Builds an OFS transaction enquiry response string with pagination metadata.
     */
    public static String buildTransactionEnquiryResponse(String accountId, List<AccountTransactionDto> transactions, int page, int size) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("//1,SUCCESS,ACCOUNT.NUMBER=%s,PAGE=%d,SIZE=%d,COUNT=%d,DATA=",
                accountId, Math.max(0, page), Math.max(1, size), transactions != null ? transactions.size() : 0));
        if (transactions != null && !transactions.isEmpty()) {
            for (int i = 0; i < transactions.size(); i++) {
                AccountTransactionDto tx = transactions.get(i);
                if (i > 0) {
                    sb.append(";;");
                }
                sb.append(tx.getTransactionId() != null ? tx.getTransactionId() : "").append(":")
                  .append(tx.getSourceAccountId() != null ? tx.getSourceAccountId() : "").append(":")
                  .append(tx.getTargetAccountId() != null ? tx.getTargetAccountId() : "").append(":")
                  .append(tx.getAmount() != null ? tx.getAmount().toPlainString() : "0.00").append(":")
                  .append(tx.getCurrency() != null ? tx.getCurrency() : "PHP").append(":")
                  .append(tx.getTransactionType() != null ? tx.getTransactionType() : "").append(":")
                  .append(tx.getStatus() != null ? tx.getStatus() : "").append(":")
                  .append(tx.getCreatedAt() != null ? tx.getCreatedAt().toString() : "");
            }
        }
        return sb.toString();
    }

    /**
     * Builds an OFS transaction enquiry response string.
     */
    public static String buildTransactionEnquiryResponse(String accountId, List<AccountTransactionDto> transactions) {
        return buildTransactionEnquiryResponse(accountId, transactions, 0, transactions != null ? transactions.size() : 0);
    }

    /**
     * Parses an OFS transaction enquiry response string into DTOs.
     */
    public static List<AccountTransactionDto> parseTransactionEnquiryResponse(String ofsResponse) {
        List<AccountTransactionDto> list = new ArrayList<>();
        if (ofsResponse == null || ofsResponse.isBlank() || !ofsResponse.startsWith("//1,SUCCESS")) {
            return list;
        }

        int dataIdx = ofsResponse.indexOf("DATA=");
        if (dataIdx < 0) {
            return list;
        }

        String data = ofsResponse.substring(dataIdx + 5).trim();
        if (data.isEmpty()) {
            return list;
        }

        String[] records = data.split(";;");
        for (String record : records) {
            if (record.isBlank()) continue;
            String[] cols = record.split(":");
            AccountTransactionDto dto = new AccountTransactionDto();
            if (cols.length > 0) dto.setTransactionId(cols[0]);
            if (cols.length > 1) dto.setSourceAccountId(cols[1]);
            if (cols.length > 2) dto.setTargetAccountId(cols[2]);
            if (cols.length > 3 && !cols[3].isBlank()) {
                try {
                    dto.setAmount(new BigDecimal(cols[3]));
                } catch (Exception ignored) {}
            }
            if (cols.length > 4) dto.setCurrency(cols[4]);
            if (cols.length > 5) dto.setTransactionType(cols[5]);
            if (cols.length > 6) dto.setStatus(cols[6]);
            if (cols.length > 7 && !cols[7].isBlank()) {
                try {
                    dto.setCreatedAt(Instant.parse(cols[7]));
                } catch (Exception ignored) {}
            }
            list.add(dto);
        }
        return list;
    }
}
