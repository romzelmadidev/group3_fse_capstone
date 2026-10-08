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
     * Parses key-value pairs from an OFS payload segment or response string.
     */
    public static Map<String, String> parseOfsFields(String ofsMessage) {
        Map<String, String> fields = new HashMap<>();
        if (ofsMessage == null || ofsMessage.isBlank()) {
            return fields;
        }

        String[] parts = ofsMessage.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;

            if (part.startsWith("//")) {
                fields.put("STATUS_CODE", part.substring(2).trim());
                continue;
            }
            if ("SUCCESS".equalsIgnoreCase(part) || "FAILURE".equalsIgnoreCase(part)) {
                fields.put("STATUS", part.toUpperCase());
                continue;
            }

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
                } else if (i == 0 && !fields.containsKey("OPERATION")) {
                    fields.put("OPERATION", part);
                } else if (i == 1 && !fields.containsKey("SUB_OPERATION")) {
                    fields.put("SUB_OPERATION", part);
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
     * Builds an OFS balance enquiry response string.
     */
    public static String buildBalanceEnquiryResponse(String accountId, String accountNumber,
                                                     BigDecimal currentBalance, BigDecimal availableBalance,
                                                     BigDecimal holdAmount, String currency) {
        return String.format(
                "//1,SUCCESS,ACCOUNT.ID=%s,ACCOUNT.NUMBER=%s,CURRENT.BALANCE=%s,AVAILABLE.BALANCE=%s,HOLD.AMOUNT=%s,CURRENCY=%s",
                accountId,
                accountNumber != null ? accountNumber : accountId,
                currentBalance != null ? currentBalance.toPlainString() : "0.0000",
                availableBalance != null ? availableBalance.toPlainString() : "0.0000",
                holdAmount != null ? holdAmount.toPlainString() : "0.0000",
                currency != null ? currency : "PHP"
        );
    }

    /**
     * Parses an OFS balance enquiry response string into key-value map with standardized keys.
     */
    public static Map<String, String> parseBalanceEnquiryResponse(String ofsResponse) {
        Map<String, String> fields = parseOfsFields(ofsResponse);
        Map<String, String> result = new HashMap<>();
        if (fields.containsKey("ACCOUNT.ID")) result.put("accountId", fields.get("ACCOUNT.ID"));
        if (fields.containsKey("ACCOUNT.NUMBER")) result.put("accountNumber", fields.get("ACCOUNT.NUMBER"));
        if (fields.containsKey("CURRENT.BALANCE")) result.put("currentBalance", fields.get("CURRENT.BALANCE"));
        if (fields.containsKey("AVAILABLE.BALANCE")) result.put("availableBalance", fields.get("AVAILABLE.BALANCE"));
        if (fields.containsKey("HOLD.AMOUNT")) result.put("holdAmount", fields.get("HOLD.AMOUNT"));
        if (fields.containsKey("CURRENCY")) result.put("currency", fields.get("CURRENCY"));
        if (fields.containsKey("STATUS")) result.put("status", fields.get("STATUS"));
        return result;
    }

    /**
     * Builds an OFS hold funds request string (Temenos AC.LOCKED.EVENTS standard).
     */
    public static String buildHoldFundsRequest(String referenceId, String accountId, BigDecimal amount,
                                               String currency, String reason) {
        return String.format(
                "AC.LOCKED.EVENTS,INPUT/I/PROCESS//%s,USER01/123456,LOCKED.EVENT.ID=%s,ACCOUNT.NUMBER=%s,AMOUNT=%s,CURRENCY=%s,DESCRIPTION=%s",
                referenceId, referenceId, accountId, amount.toPlainString(), currency != null ? currency : "PHP", reason != null ? reason : "HOLD"
        );
    }

    /**
     * Builds an OFS hold release request string (Temenos AC.LOCKED.EVENTS standard).
     */
    public static String buildHoldReleaseRequest(String referenceId, String accountId, BigDecimal amount,
                                                 String currency, String reason) {
        return String.format(
                "AC.LOCKED.EVENTS,REVERSE/I/PROCESS//%s,USER01/123456,LOCKED.EVENT.ID=%s,ACCOUNT.NUMBER=%s,AMOUNT=%s,CURRENCY=%s,DESCRIPTION=%s",
                referenceId, referenceId, accountId, amount.toPlainString(), currency != null ? currency : "PHP", reason != null ? reason : "RELEASE"
        );
    }

    /**
     * Builds an OFS hold response string.
     */
    public static String buildHoldResponse(boolean success, String holdId, String accountId,
                                           BigDecimal availableBalance, String status, String message) {
        if (success) {
            return String.format(
                    "//1,SUCCESS,HOLD.ID=%s,ACCOUNT.NUMBER=%s,AVAILABLE.BALANCE=%s,STATUS=%s,MESSAGE=%s",
                    holdId, accountId, availableBalance != null ? availableBalance.toPlainString() : "0.0000", status, message
            );
        } else {
            return String.format("//-1,FAILURE,HOLD.ID=%s,ERROR=%s", holdId, message);
        }
    }

    /**
     * Builds an OFS reversal request string.
     */
    public static String buildReversalRequestMessage(String originalCbsReference, String reason, String makerId) {
        String maker = (makerId != null && !makerId.isBlank()) ? makerId : "MAKER01";
        return String.format(
                "FUNDS.TRANSFER,REVERSAL.REQUEST/I/PROCESS//%s,%s/123456,ORIGINAL.FT.NO=%s,REASON=%s,MAKER=%s",
                originalCbsReference, maker, originalCbsReference, reason != null ? reason : "DISPUTE", maker
        );
    }

    /**
     * Builds an OFS reversal approval string.
     */
    public static String buildReversalApprovalMessage(String ticketId, String checkerId, String reason) {
        String checker = (checkerId != null && !checkerId.isBlank()) ? checkerId : "MGR02";
        return String.format(
                "FUNDS.TRANSFER,REVERSAL/I/PROCESS//%s,%s/123456,TICKET.ID=%s,CHECKER=%s,REASON=%s",
                ticketId, checker, ticketId, checker, reason != null ? reason : "APPROVED"
        );
    }

    /**
     * Builds an OFS reversal rejection string.
     */
    public static String buildReversalRejectionMessage(String ticketId, String checkerId, String reason) {
        String checker = (checkerId != null && !checkerId.isBlank()) ? checkerId : "MGR02";
        return String.format(
                "FUNDS.TRANSFER,REVERSAL.REJECT/I/PROCESS//%s,%s/123456,TICKET.ID=%s,CHECKER=%s,REASON=%s",
                ticketId, checker, ticketId, checker, reason != null ? reason : "REJECTED"
        );
    }

    /**
     * Builds an OFS reversal response string.
     */
    public static String buildReversalResponseMessage(boolean success, String ticketId, String status,
                                                      String originalTxId, String reversalTxId, String message) {
        if (success) {
            return String.format(
                    "//1,SUCCESS,TICKET.ID=%s,STATUS=%s,ORIGINAL.FT.NO=%s,REVERSAL.TX.ID=%s,MESSAGE=%s",
                    ticketId, status, originalTxId != null ? originalTxId : "", reversalTxId != null ? reversalTxId : "", message
            );
        } else {
            return String.format("//-1,FAILURE,TICKET.ID=%s,STATUS=%s,ERROR=%s", ticketId, status, message);
        }
    }

    /**
     * Builds an OFS system date response string.
     */
    public static String buildSystemDateResponse(String systemDateId, String businessDate, String status, boolean isPostingWindowOpen) {
        return String.format(
                "//1,SUCCESS,SYSTEM.DATE.ID=%s,BUSINESS.DATE=%s,STATUS=%s,POSTING.WINDOW=%s",
                systemDateId, businessDate, status, isPostingWindowOpen ? "OPEN" : "CLOSED"
        );
    }

    /**
     * Builds an OFS COB run request string.
     */
    public static String buildCobRunRequest() {
        return "BATCH.JOB,COB.RUN/I/PROCESS//SYS-BATCH,COB01/123456,OPERATION=RUN.EOD";
    }

    /**
     * Builds an OFS COB run response string.
     */
    public static String buildCobRunResponse(boolean success, String batchLogId, int accountsProcessed,
                                             BigDecimal feesCollected, BigDecimal interestAccrued,
                                             String status, String message) {
        if (success) {
            return String.format(
                    "//1,SUCCESS,BATCH.LOG.ID=%s,ACCOUNTS.PROCESSED=%d,FEES.COLLECTED=%s,INTEREST.ACCRUED=%s,STATUS=%s,MESSAGE=%s",
                    batchLogId, accountsProcessed,
                    feesCollected != null ? feesCollected.toPlainString() : "0.0000",
                    interestAccrued != null ? interestAccrued.toPlainString() : "0.0000",
                    status, message
            );
        } else {
            return String.format("//-1,FAILURE,BATCH.LOG.ID=%s,ERROR=%s", batchLogId, message);
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
