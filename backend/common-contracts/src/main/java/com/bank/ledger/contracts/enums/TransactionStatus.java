package com.bank.ledger.contracts.enums;

/**
 * Canonical Transaction Lifecycle Statuses per Target Architecture Specification.
 */
public enum TransactionStatus {
    Initiated,
    Authorized,
    Reserved,
    Processing,
    Posted,
    Failed,
    Cancelled,
    PendingReversal,
    Reversed
}
