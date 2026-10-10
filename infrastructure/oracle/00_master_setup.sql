-- ==============================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- Epic B: Database & Schema Design (Oracle XE 21c Master Database)
-- File: 00_master_setup.sql
-- Description: Master orchestrator script executing canonical schema,
--              constraints, performance indexes, seed dataset, and schema synonyms.
-- ==============================================================================

PROMPT ====================================================================
PROMPT Running Canonical Database Initialization (init.sql)...
PROMPT ====================================================================
@@init.sql

PROMPT ====================================================================
PROMPT Verification Query: Row counts in all master tables
PROMPT ====================================================================
SELECT 'USERS' AS table_name, COUNT(*) AS row_count FROM users
UNION ALL SELECT 'ACCOUNTS', COUNT(*) FROM accounts
UNION ALL SELECT 'BALANCE_MASTER', COUNT(*) FROM balance_master
UNION ALL SELECT 'TRANSACTIONS', COUNT(*) FROM transactions
UNION ALL SELECT 'OUTBOX_EVENTS', COUNT(*) FROM outbox_events
UNION ALL SELECT 'NOTIFICATIONS', COUNT(*) FROM notifications
UNION ALL SELECT 'GL_ACCOUNTS', COUNT(*) FROM gl_accounts
UNION ALL SELECT 'GL_BALANCES', COUNT(*) FROM gl_balances
UNION ALL SELECT 'SYSTEM_DATES', COUNT(*) FROM system_dates;

PROMPT ====================================================================
PROMPT Oracle XE Master Schema Setup Complete!
PROMPT ====================================================================
