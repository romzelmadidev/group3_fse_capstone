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
     * Builds an OFS message string for placing an amount hold (AC.LOCKED.EVENTS).
     */
    public static String buildAmountHold(String externalRef, String userId, String accountId,
                                        BigDecimal amount, String reason, String fromDate, String toDate) {
        String user = (userId != null && !userId.isBlank()) ? userId : "USER01";
        String cleanFrom = (fromDate != null) ? fromDate.replace("-", "") : "20261008";
        String cleanTo = (toDate != null) ? toDate.replace("-", "") : "20261009";
        String ref = (externalRef != null && !externalRef.isBlank()) ? externalRef : "HLD" + System.currentTimeMillis();
        return String.format(
                "AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1,%s/123456,,ACCOUNT.NUMBER=%s,FROM.DATE=%s,TO.DATE=%s,LOCKED.AMOUNT=%s,HOLD.REASON=%s,EXT.REF=%s",
                user, accountId, cleanFrom, cleanTo, amount.toPlainString(), reason != null ? reason : "MAKER_CHECKER_HOLD", ref
        );
    }

    /**
     * Builds an OFS message string for releasing an amount hold (AC.LOCKED.EVENTS,REVERSE).
     */
    public static String buildAmountRelease(String holdId, String userId, String accountId) {
        String user = (userId != null && !userId.isBlank()) ? userId : "USER01";
        return String.format(
                "AC.LOCKED.EVENTS,REVERSE/I/PROCESS/0/1,%s/123456,,HOLD.REF=%s,ACCOUNT.NUMBER=%s",
                user, holdId, accountId != null ? accountId : ""
        );
    }

    /**
     * Builds an OFS balance enquiry string.
     */
    public static String buildBalanceEnquiry(String accountId) {
        return String.format("ENQUIRY.SELECT,,USER01/123456,ACCOUNT.NUMBER:EQ=%s", accountId);
    }

    /**
     * Parses key-value pairs and operation metadata from an OFS protocol message string.
     */
    public static Map<String, String> parseOfsFields(String ofsMessage) {
        Map<String, String> fields = new HashMap<>();
        if (ofsMessage == null || ofsMessage.isBlank()) {
            return fields;
        }

        String cleanMessage = ofsMessage.trim();

        // 1. Detect operation type from OFS command header
        if (cleanMessage.startsWith("FUNDS.TRANSFER,REVERSAL") || cleanMessage.contains("FUNDS.TRANSFER,REVERSAL")) {
            fields.put("OPERATION", "FUNDS.TRANSFER,REVERSAL");
        } else if (cleanMessage.startsWith("FUNDS.TRANSFER,INITIATE") || cleanMessage.contains("FUNDS.TRANSFER,INITIATE")) {
            fields.put("OPERATION", "FUNDS.TRANSFER,INITIATE");
        } else if (cleanMessage.startsWith("AC.LOCKED.EVENTS,REVERSE") || cleanMessage.contains("AC.LOCKED.EVENTS,REVERSE")) {
            fields.put("OPERATION", "AC.LOCKED.EVENTS,REVERSE");
        } else if (cleanMessage.startsWith("AC.LOCKED.EVENTS,INPUT") || cleanMessage.contains("AC.LOCKED.EVENTS,INPUT") || cleanMessage.contains("AC.LOCKED.EVENTS")) {
            fields.put("OPERATION", "AC.LOCKED.EVENTS,INPUT");
        } else if (cleanMessage.startsWith("ENQUIRY.SELECT") || cleanMessage.contains("ENQUIRY.SELECT")) {
            fields.put("OPERATION", "ENQUIRY.SELECT");
        }

        // 2. Extract transaction reference embedded in GTS control header (//<ref>,)
        int gtsDoubleSlash = cleanMessage.indexOf("//");
        if (gtsDoubleSlash > 0) {
            int gtsComma = cleanMessage.indexOf(",", gtsDoubleSlash);
            if (gtsComma > gtsDoubleSlash + 2) {
                String headerRef = cleanMessage.substring(gtsDoubleSlash + 2, gtsComma).trim();
                if (!headerRef.isEmpty() && !headerRef.contains("/")) {
                    fields.put("HEADER.REF", headerRef);
                    fields.putIfAbsent("TXN.ID", headerRef);
                    fields.putIfAbsent("TICKET.ID", headerRef);
                }
            }
        }

        // 3. Parse comma-separated fields
        String[] parts = cleanMessage.split(",");
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
                } else {
                    int colonIdx = part.indexOf(":1:1=");
                    if (colonIdx > 0) {
                        String key = part.substring(0, colonIdx).trim();
                        String val = part.substring(colonIdx + 5).trim();
                        fields.put(key, val);
                    }
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
     * Builds an OFS response string for amount hold (AC.LOCKED.EVENTS).
     */
    public static String buildOfsLockedEventResponse(String lockReference, String accountId, BigDecimal amount, String holdRef) {
        return String.format(
                "%s//1/SUCCESS,ACCOUNT.NUMBER:1:1=%s,LOCKED.AMOUNT:1:1=%s,HOLD.REF:1:1=%s",
                lockReference, accountId, amount.toPlainString(), holdRef
        );
    }

    /**
     * Builds an OFS response string for amount hold release (AC.LOCKED.EVENTS,REVERSE).
     */
    public static String buildOfsLockedEventReleaseResponse(String lockReference, String holdRef) {
        return String.format("%s//1/SUCCESS,HOLD.REF:1:1=%s,MESSAGE=HOLD_RELEASED", lockReference, holdRef);
    }

    /**
     * Builds an OFS response string for amount hold capture into settlement (FUNDS.TRANSFER,AUTH with HOLD.REF).
     */
    public static String buildOfsLockedEventCaptureResponse(String txId, String holdRef, BigDecimal amount, String sourceAcc, String targetAcc) {
        return String.format(
                "%s//1/SUCCESS,HOLD.REF:1:1=%s,AMOUNT:1:1=%s,DEBIT.ACCT.NO:1:1=%s,CREDIT.ACCT.NO:1:1=%s,STATUS=CAPTURED",
                txId, holdRef, amount.toPlainString(), sourceAcc, targetAcc
        );
    }
}

