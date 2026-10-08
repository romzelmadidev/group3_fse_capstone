package com.bank.ledger.contracts.ofs;

import java.math.BigDecimal;
import java.util.HashMap;
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
     * Parses key-value pairs from an OFS payload segment.
     */
    public static Map<String, String> parseOfsFields(String ofsMessage) {
        Map<String, String> fields = new HashMap<>();
        if (ofsMessage == null || ofsMessage.isBlank()) {
            return fields;
        }

        String[] parts = ofsMessage.split(",");
        for (String part : parts) {
            int eqIdx = part.indexOf('=');
            if (eqIdx > 0) {
                String key = part.substring(0, eqIdx).trim();
                String val = part.substring(eqIdx + 1).trim();
                fields.put(key, val);
            } else {
                int colonEqIdx = part.indexOf(":EQ=");
                if (colonEqIdx > 0) {
                    String key = part.substring(0, colonEqIdx).trim();
                    String val = part.substring(colonEqIdx + 4).trim();
                    fields.put(key, val);
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
}
