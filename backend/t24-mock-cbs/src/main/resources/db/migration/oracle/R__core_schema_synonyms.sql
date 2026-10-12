-- ==============================================================================
-- Flyway Repeatable Migration: R__core_schema_synonyms.sql
-- Engine: Oracle Database 21c / 23ai
-- Description: Maintains multi-schema synonyms and grants across CORE,
--              INTEGRATION, and AUTH_IDENTITY schemas.
-- ==============================================================================

DECLARE
    TYPE t_str_list IS TABLE OF VARCHAR2(64);
    v_core_tables t_str_list := t_str_list(
        'ACCOUNTS', 'BALANCE_MASTER', 'COB_BATCH_LOG',
        'EOD_BALANCE_SNAPSHOTS', 'GL_ACCOUNTS', 'GL_BALANCES', 'GL_LEDGER',
        'INTEREST_ACCRUALS', 'PARTICIPANT_BANKS', 'REVERSAL_REQUESTS', 'SYSTEM_DATES',
        'TRANSACTIONS', 'TRANSACTION_STATUS_HISTORY', 'UNCOLLECTED_FEES',
        'USERS', 'ADMIN_USERS'
    );
    v_integration_tables t_str_list := t_str_list('OUTBOX_EVENTS');
    v_auth_tables t_str_list := t_str_list('USERS', 'ADMIN_USERS');
BEGIN
    FOR i IN 1..v_core_tables.COUNT LOOP
        BEGIN
            EXECUTE IMMEDIATE 'GRANT ALL ON fse_user.' || v_core_tables(i) || ' TO core';
            EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM core.' || v_core_tables(i) || ' FOR fse_user.' || v_core_tables(i);
            EXECUTE IMMEDIATE 'GRANT ALL ON core.' || v_core_tables(i) || ' TO fse_user';
        EXCEPTION WHEN OTHERS THEN NULL; END;
    END LOOP;

    BEGIN
        EXECUTE IMMEDIATE 'GRANT ALL ON fse_user.v_all_users TO core';
        EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM core.v_all_users FOR fse_user.v_all_users';
    EXCEPTION WHEN OTHERS THEN NULL; END;

    BEGIN
        EXECUTE IMMEDIATE 'GRANT ALL ON fse_user.v_all_users TO auth_identity';
        EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM auth_identity.v_all_users FOR fse_user.v_all_users';
    EXCEPTION WHEN OTHERS THEN NULL; END;

    FOR i IN 1..v_integration_tables.COUNT LOOP
        BEGIN
            EXECUTE IMMEDIATE 'GRANT ALL ON fse_user.' || v_integration_tables(i) || ' TO integration';
            EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM integration.' || v_integration_tables(i) || ' FOR fse_user.' || v_integration_tables(i);
            EXECUTE IMMEDIATE 'GRANT ALL ON integration.' || v_integration_tables(i) || ' TO fse_user';
        EXCEPTION WHEN OTHERS THEN NULL; END;
    END LOOP;

    FOR i IN 1..v_auth_tables.COUNT LOOP
        BEGIN
            EXECUTE IMMEDIATE 'GRANT ALL ON fse_user.' || v_auth_tables(i) || ' TO auth_identity';
            EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM auth_identity.' || v_auth_tables(i) || ' FOR fse_user.' || v_auth_tables(i);
            EXECUTE IMMEDIATE 'GRANT ALL ON auth_identity.' || v_auth_tables(i) || ' TO fse_user';
        EXCEPTION WHEN OTHERS THEN NULL; END;
    END LOOP;
END;
/
