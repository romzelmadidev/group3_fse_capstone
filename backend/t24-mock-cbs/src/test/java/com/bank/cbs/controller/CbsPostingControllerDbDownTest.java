package com.bank.cbs.controller;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CbsPostingControllerDbDownTest {

    @Test
    void lostConnectionIsDatabaseDown() {
        var hikari = new java.sql.SQLTransientConnectionException("Connection is not available");
        assertTrue(CbsPostingController.isDatabaseDown(
                new RuntimeException(new CannotCreateTransactionException("Could not open JPA EntityManager", hikari))));
    }

    @Test
    void businessAndConstraintErrorsAreNotDatabaseDown() {
        assertFalse(CbsPostingController.isDatabaseDown(new IllegalStateException("Insufficient funds")));
        assertFalse(CbsPostingController.isDatabaseDown(new DataIntegrityViolationException("unique constraint")));
    }
}
