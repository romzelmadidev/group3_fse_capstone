-- ==============================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- Migration: Drop amount_holds table and constraints (Holds Decommissioned)
-- File: 06_drop_amount_holds_table.sql
-- ==============================================================================

SET ECHO ON;
SET FEEDBACK ON;

PROMPT Dropping amount_holds table and indices...

BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE amount_holds CASCADE CONSTRAINTS PURGE';
    DBMS_OUTPUT.PUT_LINE('amount_holds table successfully dropped.');
EXCEPTION 
    WHEN OTHERS THEN 
        IF SQLCODE = -942 THEN 
            DBMS_OUTPUT.PUT_LINE('amount_holds table does not exist. Skipping.');
        ELSE 
            RAISE; 
        END IF;
END;
/

PROMPT Migration 06_drop_amount_holds_table completed.
