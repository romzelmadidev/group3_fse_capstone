-- ==============================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- Epic B: Database & Schema Design (Oracle XE 21c Master Database)
-- File: 04_cleanup_schema.sql
-- Description: Drops all created tables, triggers, and constraints safely.
-- ==============================================================================

SET ECHO ON;
SET FEEDBACK ON;

PROMPT Dropping Oracle XE Master Tables in reverse dependency order...

DECLARE
    TYPE t_table_list IS TABLE OF VARCHAR2(64);
    v_tables t_table_list := t_table_list(
        'TRANSACTION_STATUS_HISTORY', 'REVERSAL_REQUESTS', 'UNCOLLECTED_FEES',
        'INTEREST_ACCRUALS', 'EOD_BALANCE_SNAPSHOTS', 'COB_BATCH_LOG',
        'GL_LEDGER', 'GL_BALANCES', 'GL_ACCOUNTS', 'SYSTEM_DATES',
        'OUTBOX_EVENTS', 'NOTIFICATIONS', 'AUTH_SESSIONS', 'TRANSACTIONS',
        'CREDIT_ASSESSMENTS', 'BALANCE_MASTER', 'ACCOUNTS', 'USERS',
        'CUSTOMER_BALANCE_MASTER', 'BILLS_PAYMENT', 'TRANSFERS', 'USER_ROLES', 'ROLES'
    );
BEGIN
    FOR i IN 1..v_tables.COUNT LOOP
        BEGIN
            EXECUTE IMMEDIATE 'DROP TABLE ' || v_tables(i) || ' CASCADE CONSTRAINTS PURGE';
        EXCEPTION WHEN OTHERS THEN
            IF SQLCODE != -942 THEN RAISE; END IF;
        END;
    END LOOP;
END;
/

PROMPT All Oracle XE Master Tables successfully dropped and purged.
