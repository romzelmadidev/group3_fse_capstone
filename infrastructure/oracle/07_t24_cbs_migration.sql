-- ==============================================================================
-- CAPSTONE FSE: T24 Core Banking Master Schema Migration
-- File: 07_t24_cbs_migration.sql
-- Description: Applies missing T24 Core tables, gl_accounts, system_dates,
--              status history, and reconciles transactions columns (idempotency_key,
--              source_account_id, target_account_id, etc.) for Oracle XE.
-- ==============================================================================

SET ECHO ON;
SET FEEDBACK ON;

-- 1. Upgrade transactions table columns
DECLARE
  v_count NUMBER;
BEGIN
  SELECT COUNT(*) INTO v_count FROM user_tab_cols WHERE table_name = 'TRANSACTIONS' AND column_name = 'IDEMPOTENCY_KEY';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD (idempotency_key VARCHAR2(64) UNIQUE)';
    DBMS_OUTPUT.PUT_LINE('Added transactions.idempotency_key');
  END IF;

  SELECT COUNT(*) INTO v_count FROM user_tab_cols WHERE table_name = 'TRANSACTIONS' AND column_name = 'SOURCE_ACCOUNT_ID';
  IF v_count > 0 THEN
    BEGIN
      EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP COLUMN source_account_id';
      DBMS_OUTPUT.PUT_LINE('Dropped redundant transactions.source_account_id');
    EXCEPTION WHEN OTHERS THEN NULL; END;
  END IF;

  SELECT COUNT(*) INTO v_count FROM user_tab_cols WHERE table_name = 'TRANSACTIONS' AND column_name = 'TARGET_ACCOUNT_ID';
  IF v_count > 0 THEN
    BEGIN
      EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP COLUMN target_account_id';
      DBMS_OUTPUT.PUT_LINE('Dropped redundant transactions.target_account_id');
    EXCEPTION WHEN OTHERS THEN NULL; END;
  END IF;

  SELECT COUNT(*) INTO v_count FROM user_tab_cols WHERE table_name = 'TRANSACTIONS' AND column_name = 'TRANSACTION_TYPE';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD (transaction_type VARCHAR2(30) DEFAULT ''INTRA_BANK'')';
    DBMS_OUTPUT.PUT_LINE('Added transactions.transaction_type');
  END IF;

  SELECT COUNT(*) INTO v_count FROM user_tab_cols WHERE table_name = 'TRANSACTIONS' AND column_name = 'REQUIRES_MAKER_CHECKER';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD (requires_maker_checker NUMBER(1) DEFAULT 0)';
    DBMS_OUTPUT.PUT_LINE('Added transactions.requires_maker_checker');
  END IF;

  SELECT COUNT(*) INTO v_count FROM user_tab_cols WHERE table_name = 'TRANSACTIONS' AND column_name = 'APPROVED_BY';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD (approved_by VARCHAR2(64))';
    DBMS_OUTPUT.PUT_LINE('Added transactions.approved_by');
  END IF;
END;
/

-- 2. Relax legacy NOT NULL columns on transactions
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE transactions MODIFY (from_account_id NULL)';
  EXECUTE IMMEDIATE 'ALTER TABLE transactions MODIFY (type NULL)';
  EXECUTE IMMEDIATE 'ALTER TABLE transactions MODIFY (before_balance NUMBER(18, 4) DEFAULT 0.0000 NULL)';
  EXECUTE IMMEDIATE 'ALTER TABLE transactions MODIFY (after_balance NUMBER(18, 4) DEFAULT 0.0000 NULL)';
  EXECUTE IMMEDIATE 'ALTER TABLE transactions MODIFY (requires_2fa_otp NUMBER(1) DEFAULT 0 NULL)';
EXCEPTION
  WHEN OTHERS THEN
    DBMS_OUTPUT.PUT_LINE('Error modifying legacy nullability: ' || SQLERRM);
END;
/

-- 3. Relax check constraints on transactions
BEGIN
  FOR c IN (SELECT constraint_name FROM user_constraints WHERE table_name = 'TRANSACTIONS' AND constraint_name IN ('CHK_TX_TYPE', 'CHK_TX_STATUS')) LOOP
    BEGIN
      EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP CONSTRAINT ' || c.constraint_name;
    EXCEPTION WHEN OTHERS THEN NULL; END;
  END LOOP;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD CONSTRAINT chk_tx_status CHECK (UPPER(status) IN (''INITIATED'', ''AUTHORIZED'', ''RESERVED'', ''PROCESSING'', ''POSTED'', ''FAILED'', ''CANCELLED'', ''PENDINGREVERSAL'', ''PENDING_REVERSAL'', ''REVERSED'', ''PENDING_APPROVAL'', ''COMMITTED'', ''REJECTED_FRAUD''))';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE transactions ADD CONSTRAINT chk_tx_type CHECK (type IS NULL OR UPPER(type) IN (''DEPOSIT'', ''WITHDRAWAL'', ''TRANSFER'', ''REVERSAL'', ''INTRA_BANK'', ''INTER_BANK''))';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- 4. Sync existing transactions rows
UPDATE transactions SET
  transaction_type = COALESCE(transaction_type, type, 'INTRA_BANK'),
  requires_maker_checker = COALESCE(requires_maker_checker, requires_2fa_otp, 0),
  approved_by = COALESCE(approved_by, approved_by_user_id),
  idempotency_key = COALESCE(idempotency_key, transaction_id);
COMMIT;

-- 5. Bidirectional sync trigger on transactions
CREATE OR REPLACE TRIGGER trg_tx_sync_cols
BEFORE INSERT OR UPDATE ON transactions
FOR EACH ROW
BEGIN
    IF :NEW.type IS NULL AND :NEW.transaction_type IS NOT NULL THEN
        :NEW.type := :NEW.transaction_type;
    END IF;
    IF :NEW.transaction_type IS NULL AND :NEW.type IS NOT NULL THEN
        :NEW.transaction_type := :NEW.type;
    END IF;
    IF :NEW.before_balance IS NULL THEN
        :NEW.before_balance := 0.0000;
    END IF;
    IF :NEW.after_balance IS NULL THEN
        :NEW.after_balance := 0.0000;
    END IF;
    IF :NEW.requires_maker_checker IS NULL AND :NEW.requires_2fa_otp IS NOT NULL THEN
        :NEW.requires_maker_checker := :NEW.requires_2fa_otp;
    END IF;
    IF :NEW.requires_2fa_otp IS NULL AND :NEW.requires_maker_checker IS NOT NULL THEN
        :NEW.requires_2fa_otp := :NEW.requires_maker_checker;
    END IF;
    IF :NEW.approved_by_user_id IS NULL AND :NEW.approved_by IS NOT NULL THEN
        :NEW.approved_by_user_id := :NEW.approved_by;
    END IF;
    IF :NEW.approved_by IS NULL AND :NEW.approved_by_user_id IS NOT NULL THEN
        :NEW.approved_by := :NEW.approved_by_user_id;
    END IF;
    IF :NEW.idempotency_key IS NULL THEN
        :NEW.idempotency_key := :NEW.transaction_id;
    END IF;
END;
/

-- 6. Trigger for balance_master balance_id defaulting
CREATE OR REPLACE TRIGGER trg_bm_ensure_balance_id
BEFORE INSERT OR UPDATE ON balance_master
FOR EACH ROW
BEGIN
    IF :NEW.balance_id IS NULL THEN
        :NEW.balance_id := 'BM-' || :NEW.account_id;
    END IF;
END;
/

-- 7. Create missing T24 Core Banking Tables
DECLARE
  v_count NUMBER;
BEGIN
  -- GL_ACCOUNTS
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'GL_ACCOUNTS';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE gl_accounts (
        gl_code      VARCHAR2(32) PRIMARY KEY,
        account_name VARCHAR2(100) NOT NULL,
        account_type VARCHAR2(20) NOT NULL CHECK (account_type IN (''ASSET'', ''LIABILITY'', ''EQUITY'', ''REVENUE'', ''EXPENSE'')),
        currency     VARCHAR2(3) DEFAULT ''PHP'' NOT NULL,
        is_active    NUMBER(1) DEFAULT 1 NOT NULL CHECK (is_active IN (0, 1))
    )';
    DBMS_OUTPUT.PUT_LINE('Created gl_accounts');
  END IF;

  -- GL_LEDGER
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'GL_LEDGER';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE gl_ledger (
        journal_id     VARCHAR2(64) PRIMARY KEY,
        transaction_id VARCHAR2(64) NOT NULL,
        gl_code        VARCHAR2(32) NOT NULL,
        debit_amount   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        credit_amount  NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        posting_date   DATE NOT NULL,
        created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT fk_gl_account FOREIGN KEY (gl_code) REFERENCES gl_accounts(gl_code),
        CONSTRAINT chk_gl_amounts CHECK (debit_amount >= 0 AND credit_amount >= 0)
    )';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_gl_ledger_tx ON gl_ledger(transaction_id)';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_gl_ledger_date ON gl_ledger(posting_date, gl_code)';
    DBMS_OUTPUT.PUT_LINE('Created gl_ledger');
  END IF;

  -- GL_BALANCES
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'GL_BALANCES';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE gl_balances (
        gl_code       VARCHAR2(32) NOT NULL,
        fiscal_period VARCHAR2(20) NOT NULL,
        total_debit   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        total_credit  NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        net_balance   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT pk_gl_balances PRIMARY KEY (gl_code, fiscal_period),
        CONSTRAINT fk_glb_account FOREIGN KEY (gl_code) REFERENCES gl_accounts(gl_code)
    )';
    DBMS_OUTPUT.PUT_LINE('Created gl_balances');
  END IF;

  -- SYSTEM_DATES
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'SYSTEM_DATES';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE system_dates (
        system_date_id        VARCHAR2(64) PRIMARY KEY,
        business_date         DATE NOT NULL,
        status                VARCHAR2(30) DEFAULT ''ONLINE'' NOT NULL CHECK (status IN (''ONLINE'', ''EOD_CUTOFF'', ''COB_PROCESSING'', ''ROLLOVER'', ''ERROR_HALTED'')),
        posting_window_open   NUMBER(1) DEFAULT 1 NOT NULL CHECK (posting_window_open IN (0, 1)),
        last_cob_completed_at TIMESTAMP WITH TIME ZONE,
        updated_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
    )';
    DBMS_OUTPUT.PUT_LINE('Created system_dates');
  END IF;

  -- COB_BATCH_LOG
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'COB_BATCH_LOG';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE cob_batch_log (
        batch_id               VARCHAR2(64) PRIMARY KEY,
        business_date          DATE NOT NULL,
        started_at             TIMESTAMP WITH TIME ZONE NOT NULL,
        completed_at           TIMESTAMP WITH TIME ZONE,
        status                 VARCHAR2(20) DEFAULT ''RUNNING'' NOT NULL CHECK (status IN (''RUNNING'', ''COMPLETED'', ''FAILED'')),
        current_phase          VARCHAR2(50),
        accounts_processed     NUMBER(10) DEFAULT 0 NOT NULL,
        total_fees_collected   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        total_interest_accrued NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        total_tax_withheld     NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        error_message          VARCHAR2(1000)
    )';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_cob_log_date ON cob_batch_log(business_date)';
    DBMS_OUTPUT.PUT_LINE('Created cob_batch_log');
  END IF;

  -- TRANSACTION_STATUS_HISTORY
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'TRANSACTION_STATUS_HISTORY';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE transaction_status_history (
        history_id     VARCHAR2(64) PRIMARY KEY,
        transaction_id VARCHAR2(64) NOT NULL,
        from_status    VARCHAR2(30),
        to_status      VARCHAR2(30) NOT NULL,
        change_reason  VARCHAR2(100) NOT NULL,
        reason_details VARCHAR2(500),
        actor_id       VARCHAR2(64) NOT NULL,
        actor_type     VARCHAR2(30) NOT NULL,
        changed_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        metadata_json  CLOB,
        CONSTRAINT fk_tsh_tx FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id)
    )';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_tsh_tx ON transaction_status_history(transaction_id, changed_at ASC)';
    DBMS_OUTPUT.PUT_LINE('Created transaction_status_history');
  END IF;

  -- REVERSAL_REQUESTS
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'REVERSAL_REQUESTS';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE reversal_requests (
        ticket_id             VARCHAR2(64) PRIMARY KEY,
        original_tx_id        VARCHAR2(64) NOT NULL,
        maker_id              VARCHAR2(64) NOT NULL,
        checker_id            VARCHAR2(64),
        dispute_reason        VARCHAR2(100) NOT NULL,
        maker_notes           VARCHAR2(500) NOT NULL,
        checker_notes         VARCHAR2(500),
        status                VARCHAR2(20) DEFAULT ''PENDING'' NOT NULL CHECK (status IN (''PENDING'', ''APPROVED'', ''REJECTED'')),
        reversal_tx_id        VARCHAR2(64),
        created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        resolved_at           TIMESTAMP WITH TIME ZONE,
        CONSTRAINT fk_rev_orig_tx FOREIGN KEY (original_tx_id) REFERENCES transactions(transaction_id)
    )';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_rev_orig_tx ON reversal_requests(original_tx_id)';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_rev_status ON reversal_requests(status)';
    DBMS_OUTPUT.PUT_LINE('Created reversal_requests');
  END IF;

  -- UNCOLLECTED_FEES
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'UNCOLLECTED_FEES';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE uncollected_fees (
        fee_id           VARCHAR2(64) PRIMARY KEY,
        account_id       VARCHAR2(64) NOT NULL,
        fee_type         VARCHAR2(50) NOT NULL,
        amount_due       NUMBER(18, 4) NOT NULL,
        amount_collected NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        is_settled       NUMBER(1) DEFAULT 0 NOT NULL CHECK (is_settled IN (0, 1)),
        created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT fk_uncol_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id)
    )';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_uncollected_acc ON uncollected_fees(account_id, is_settled)';
    DBMS_OUTPUT.PUT_LINE('Created uncollected_fees');
  END IF;

  -- INTEREST_ACCRUALS
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'INTEREST_ACCRUALS';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE interest_accruals (
        accrual_id     VARCHAR2(64) PRIMARY KEY,
        account_id     VARCHAR2(64) NOT NULL,
        accrual_date   DATE NOT NULL,
        daily_rate     NUMBER(12, 8) NOT NULL,
        accrued_amount NUMBER(18, 4) NOT NULL,
        tax_withheld   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
        net_accrual    NUMBER(18, 4) NOT NULL,
        is_capitalized NUMBER(1) DEFAULT 0 NOT NULL CHECK (is_capitalized IN (0, 1)),
        created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT fk_int_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id)
    )';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_int_acc_date ON interest_accruals(account_id, accrual_date)';
    DBMS_OUTPUT.PUT_LINE('Created interest_accruals');
  END IF;

  -- EOD_BALANCE_SNAPSHOTS
  SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'EOD_BALANCE_SNAPSHOTS';
  IF v_count = 0 THEN
    EXECUTE IMMEDIATE 'CREATE TABLE eod_balance_snapshots (
        snapshot_id     VARCHAR2(64) PRIMARY KEY,
        account_id      VARCHAR2(64) NOT NULL,
        business_date   DATE NOT NULL,
        closing_balance NUMBER(18, 4) NOT NULL,
        frozen_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT fk_snap_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id)
    )';
    EXECUTE IMMEDIATE 'CREATE INDEX idx_eod_snap_date ON eod_balance_snapshots(business_date, account_id)';
    DBMS_OUTPUT.PUT_LINE('Created eod_balance_snapshots');
  END IF;
END;
/

-- 8. Seed Baseline Chart of Accounts & System Dates
MERGE INTO gl_accounts target
USING (
    SELECT '1010-CASH-VAULT' AS gl_code, 'Cash and Cash Equivalents Vault' AS account_name, 'ASSET' AS account_type, 'PHP' AS currency, 1 AS is_active FROM dual UNION ALL
    SELECT '2100-CUST-LIAB', 'Customer Deposit Liabilities (Subledger Control)', 'LIABILITY', 'PHP', 1 FROM dual UNION ALL
    SELECT '20100', 'Customer Demand Deposits', 'LIABILITY', 'PHP', 1 FROM dual UNION ALL
    SELECT '4010-FEE-INCOME', 'Fee and Commission Income', 'REVENUE', 'PHP', 1 FROM dual UNION ALL
    SELECT '5010-INT-EXPENSE', 'Deposit Interest Expense', 'EXPENSE', 'PHP', 1 FROM dual UNION ALL
    SELECT '2150-TAX-WITHHOLD-PAYABLE', 'BIR Final Withholding Tax Payable (20%)', 'LIABILITY', 'PHP', 1 FROM dual
) source
ON (target.gl_code = source.gl_code)
WHEN NOT MATCHED THEN
    INSERT (gl_code, account_name, account_type, currency, is_active)
    VALUES (source.gl_code, source.account_name, source.account_type, source.currency, source.is_active);

MERGE INTO gl_balances target
USING (
    SELECT '1010-CASH-VAULT' AS gl_code, '2026-10' AS fiscal_period FROM dual UNION ALL
    SELECT '2100-CUST-LIAB', '2026-10' FROM dual UNION ALL
    SELECT '20100', '2026-10' FROM dual UNION ALL
    SELECT '20100', '2026-M10' FROM dual UNION ALL
    SELECT '4010-FEE-INCOME', '2026-10' FROM dual UNION ALL
    SELECT '5010-INT-EXPENSE', '2026-10' FROM dual UNION ALL
    SELECT '2150-TAX-WITHHOLD-PAYABLE', '2026-10' FROM dual
) source
ON (target.gl_code = source.gl_code AND target.fiscal_period = source.fiscal_period)
WHEN NOT MATCHED THEN
    INSERT (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
    VALUES (source.gl_code, source.fiscal_period, 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

MERGE INTO system_dates target
USING (
    SELECT 'SYS-DATE-001' AS system_date_id, DATE '2026-10-07' AS business_date, 'ONLINE' AS status, 1 AS posting_window_open FROM dual UNION ALL
    SELECT 'SYS-DATE-1' AS system_date_id, TRUNC(CURRENT_DATE) AS business_date, 'ONLINE' AS status, 1 AS posting_window_open FROM dual
) source
ON (target.system_date_id = source.system_date_id)
WHEN NOT MATCHED THEN
    INSERT (system_date_id, business_date, status, posting_window_open, updated_at)
    VALUES (source.system_date_id, source.business_date, source.status, source.posting_window_open, CURRENT_TIMESTAMP);

COMMIT;

-- 9. Grants and Synonyms for CORE, INTEGRATION, and AUTH_IDENTITY
DECLARE
    TYPE t_str_list IS TABLE OF VARCHAR2(64);
    v_core_tables t_str_list := t_str_list(
        'ACCOUNTS', 'BALANCE_MASTER', 'COB_BATCH_LOG',
        'EOD_BALANCE_SNAPSHOTS', 'GL_ACCOUNTS', 'GL_BALANCES', 'GL_LEDGER',
        'INTEREST_ACCRUALS', 'REVERSAL_REQUESTS', 'SYSTEM_DATES',
        'TRANSACTIONS', 'TRANSACTION_STATUS_HISTORY', 'UNCOLLECTED_FEES',
        'USERS'
    );
    v_integration_tables t_str_list := t_str_list('OUTBOX_EVENTS');
    v_auth_tables t_str_list := t_str_list('USERS');
BEGIN
    FOR i IN 1..v_core_tables.COUNT LOOP
        BEGIN
            EXECUTE IMMEDIATE 'GRANT ALL ON fse_user.' || v_core_tables(i) || ' TO core';
            EXECUTE IMMEDIATE 'CREATE OR REPLACE SYNONYM core.' || v_core_tables(i) || ' FOR fse_user.' || v_core_tables(i);
            EXECUTE IMMEDIATE 'GRANT ALL ON core.' || v_core_tables(i) || ' TO fse_user';
        EXCEPTION WHEN OTHERS THEN NULL; END;
    END LOOP;

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

COMMIT;
