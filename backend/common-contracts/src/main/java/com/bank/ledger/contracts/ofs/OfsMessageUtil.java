package com.bank.ledger.contracts.ofs;

import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.dto.ReversalTicketDto;
import com.bank.ledger.contracts.dto.TransactionStatusHistoryDto;

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
                } else {
                    int colonIdx = part.indexOf(":1:1=");
                    if (colonIdx > 0) {
                        String key = part.substring(0, colonIdx).trim();
                        String val = part.substring(colonIdx + 5).trim();
                        fields.put(key, val);
                    } else if (i == 0 && !fields.containsKey("OPERATION")) {
                        fields.put("OPERATION", part);
                    } else if (i == 1 && !fields.containsKey("SUB_OPERATION")) {
                        fields.put("SUB_OPERATION", part);
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

    /**
     * Builds an OFS reversal list enquiry response string with pagination metadata.
     */
    public static String buildReversalListResponse(List<ReversalTicketDto> tickets, int page, int size) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("//1,SUCCESS,OPERATION=REVERSAL.LIST,PAGE=%d,SIZE=%d,COUNT=%d,DATA=",
                Math.max(0, page), Math.max(1, size), tickets != null ? tickets.size() : 0));
        if (tickets != null && !tickets.isEmpty()) {
            for (int i = 0; i < tickets.size(); i++) {
                ReversalTicketDto t = tickets.get(i);
                if (i > 0) {
                    sb.append(";;");
                }
                String reason = t.getDisputeReason() != null ? t.getDisputeReason().replace(":", "-").replace(";", ",") : "";
                String mNotes = t.getMakerNotes() != null ? t.getMakerNotes().replace(":", "-").replace(";", ",") : "";
                String cNotes = t.getCheckerNotes() != null ? t.getCheckerNotes().replace(":", "-").replace(";", ",") : "";
                sb.append(t.getTicketId() != null ? t.getTicketId() : "").append(":")
                  .append(t.getOriginalTransactionId() != null ? t.getOriginalTransactionId() : "").append(":")
                  .append(t.getMakerId() != null ? t.getMakerId() : "").append(":")
                  .append(t.getCheckerId() != null ? t.getCheckerId() : "").append(":")
                  .append(t.getStatus() != null ? t.getStatus() : "").append(":")
                  .append(reason).append(":")
                  .append(mNotes).append(":")
                  .append(cNotes).append(":")
                  .append(t.getReversalTransactionId() != null ? t.getReversalTransactionId() : "").append(":")
                  .append(t.getCreatedAt() != null ? t.getCreatedAt().toString() : "").append(":")
                  .append(t.getResolvedAt() != null ? t.getResolvedAt().toString() : "");
            }
        }
        return sb.toString();
    }

    /**
     * Parses an OFS reversal list enquiry response string into DTOs.
     */
    public static List<ReversalTicketDto> parseReversalListResponse(String ofsResponse) {
        List<ReversalTicketDto> list = new ArrayList<>();
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
            ReversalTicketDto dto = new ReversalTicketDto();
            if (cols.length > 0) dto.setTicketId(cols[0]);
            if (cols.length > 1) dto.setOriginalTransactionId(cols[1]);
            if (cols.length > 2) dto.setMakerId(cols[2]);
            if (cols.length > 3) dto.setCheckerId(cols[3]);
            if (cols.length > 4) dto.setStatus(cols[4]);
            if (cols.length > 5) dto.setDisputeReason(cols[5]);
            if (cols.length > 6) dto.setMakerNotes(cols[6]);
            if (cols.length > 7) dto.setCheckerNotes(cols[7]);
            if (cols.length > 8) dto.setReversalTransactionId(cols[8]);
            if (cols.length > 9 && !cols[9].isBlank()) {
                try {
                    dto.setCreatedAt(Instant.parse(cols[9]));
                } catch (Exception ignored) {}
            }
            if (cols.length > 10 && !cols[10].isBlank()) {
                try {
                    dto.setResolvedAt(Instant.parse(cols[10]));
                } catch (Exception ignored) {}
            }
            list.add(dto);
        }
        return list;
    }

    /**
     * Builds an OFS transaction status history response string with pagination metadata.
     */
    public static String buildStatusHistoryResponse(String transactionId, List<TransactionStatusHistoryDto> history, int page, int size) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("//1,SUCCESS,OPERATION=STATUS.HISTORY,TRANSACTION.ID=%s,PAGE=%d,SIZE=%d,COUNT=%d,DATA=",
                transactionId, Math.max(0, page), Math.max(1, size), history != null ? history.size() : 0));
        if (history != null && !history.isEmpty()) {
            for (int i = 0; i < history.size(); i++) {
                TransactionStatusHistoryDto h = history.get(i);
                if (i > 0) {
                    sb.append(";;");
                }
                String details = h.getReasonDetails() != null ? h.getReasonDetails().replace(":", "-").replace(";", ",") : "";
                sb.append(h.getHistoryId() != null ? h.getHistoryId() : "").append(":")
                  .append(h.getTransactionId() != null ? h.getTransactionId() : "").append(":")
                  .append(h.getFromStatus() != null ? h.getFromStatus() : "").append(":")
                  .append(h.getToStatus() != null ? h.getToStatus() : "").append(":")
                  .append(h.getChangeReason() != null ? h.getChangeReason() : "").append(":")
                  .append(h.getActorId() != null ? h.getActorId() : "").append(":")
                  .append(h.getActorType() != null ? h.getActorType() : "").append(":")
                  .append(h.getChangedAt() != null ? h.getChangedAt().toString() : "").append(":")
                  .append(details);
            }
        }
        return sb.toString();
    }

    /**
     * Parses an OFS transaction status history response string into DTOs.
     */
    public static List<TransactionStatusHistoryDto> parseStatusHistoryResponse(String ofsResponse) {
        List<TransactionStatusHistoryDto> list = new ArrayList<>();
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
            TransactionStatusHistoryDto dto = new TransactionStatusHistoryDto();
            if (cols.length > 0) dto.setHistoryId(cols[0]);
            if (cols.length > 1) dto.setTransactionId(cols[1]);
            if (cols.length > 2) dto.setFromStatus(cols[2]);
            if (cols.length > 3) dto.setToStatus(cols[3]);
            if (cols.length > 4) dto.setChangeReason(cols[4]);
            if (cols.length > 5) dto.setActorId(cols[5]);
            if (cols.length > 6) dto.setActorType(cols[6]);
            if (cols.length > 7 && !cols[7].isBlank()) {
                try {
                    dto.setChangedAt(Instant.parse(cols[7]));
                } catch (Exception ignored) {}
            }
            if (cols.length > 8) dto.setReasonDetails(cols[8]);
            list.add(dto);
        }
        return list;
    }
}
