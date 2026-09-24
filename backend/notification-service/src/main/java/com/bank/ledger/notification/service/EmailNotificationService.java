package com.bank.ledger.notification.service;

import com.bank.ledger.contracts.dto.TransactionNotificationEvent;

import java.util.Map;

public interface EmailNotificationService {

    /**
     * Dispatches HTML transaction receipt email to customer inbox.
     * Uses Redis deduplication to prevent duplicate sends on Kafka rebalance.
     */
    boolean sendTransactionReceipt(TransactionNotificationEvent event);

    /**
     * Dispatches high-priority Maker-Checker compliance alert email to tellers/supervisors.
     */
    boolean sendMakerCheckerAlert(TransactionNotificationEvent event);

    /**
     * Returns the current status of the retry buffer spool.
     */
    Map<String, Object> getSpoolStatus();

    /**
     * Flushes buffered retry emails manually.
     */
    int flushRetrySpool();
}
