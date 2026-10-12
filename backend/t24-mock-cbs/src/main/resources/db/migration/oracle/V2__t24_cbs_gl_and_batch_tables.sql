-- ==============================================================================
-- Flyway Migration: V2__t24_cbs_gl_and_batch_tables.sql
-- Engine: Oracle Database 21c / 23ai
-- Description: Core Banking tables for General Ledger, Close of Business (COB)
--              batch lifecycle, reversal tickets, and foundational reference data.
-- ==============================================================================

-- 1. Table: gl_accounts (Chart of Accounts)
CREATE TABLE gl_accounts (
    gl_code      VARCHAR2(32) PRIMARY KEY,
    account_name VARCHAR2(100) NOT NULL,
    account_type VARCHAR2(20) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency     VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    is_active    NUMBER(1) DEFAULT 1 NOT NULL CHECK (is_active IN (0, 1))
);

-- 2. Table: gl_ledger (Double-Entry Journal Postings)
CREATE TABLE gl_ledger (
    journal_id     VARCHAR2(64) PRIMARY KEY,
    transaction_id VARCHAR2(64) NOT NULL,
    gl_code        VARCHAR2(32) NOT NULL,
    debit_amount   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    credit_amount  NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    posting_date   DATE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_gl_account FOREIGN KEY (gl_code) REFERENCES gl_accounts(gl_code),
    CONSTRAINT chk_gl_amounts CHECK (debit_amount >= 0 AND credit_amount >= 0)
);

CREATE INDEX idx_gl_ledger_tx ON gl_ledger(transaction_id);
CREATE INDEX idx_gl_ledger_date ON gl_ledger(posting_date, gl_code);

-- 3. Table: gl_balances (Real-Time Accumulators)
CREATE TABLE gl_balances (
    gl_code       VARCHAR2(32) NOT NULL,
    fiscal_period VARCHAR2(20) NOT NULL,
    total_debit   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    total_credit  NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    net_balance   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_gl_balances PRIMARY KEY (gl_code, fiscal_period),
    CONSTRAINT fk_glb_account FOREIGN KEY (gl_code) REFERENCES gl_accounts(gl_code)
);

-- 4. Table: reversal_requests
CREATE TABLE reversal_requests (
    ticket_id                VARCHAR2(64) PRIMARY KEY,
    original_tx_id           VARCHAR2(64),
    original_transaction_id  VARCHAR2(64),
    maker_id                 VARCHAR2(64) NOT NULL,
    checker_id               VARCHAR2(64),
    dispute_reason           VARCHAR2(100) NOT NULL,
    maker_notes              VARCHAR2(500),
    checker_notes            VARCHAR2(500),
    status                   VARCHAR2(30) DEFAULT 'PENDING' NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reversal_tx_id           VARCHAR2(64),
    reversal_transaction_id  VARCHAR2(64),
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    resolved_at              TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_rev_orig_tx FOREIGN KEY (original_tx_id) REFERENCES transactions(transaction_id)
);

CREATE INDEX idx_rev_orig_tx ON reversal_requests(original_tx_id);
CREATE INDEX idx_rev_status ON reversal_requests(status);
CREATE INDEX idx_rev_stat_dt ON reversal_requests(status, created_at DESC);

-- 5. Table: uncollected_fees
CREATE TABLE uncollected_fees (
    fee_id           VARCHAR2(64) PRIMARY KEY,
    account_id       VARCHAR2(64) NOT NULL,
    fee_type         VARCHAR2(50) NOT NULL,
    amount_due       NUMBER(18, 4) NOT NULL,
    amount_collected NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    is_settled       NUMBER(1) DEFAULT 0 NOT NULL CHECK (is_settled IN (0, 1)),
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_uncol_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_settled_integrity CHECK (
        (is_settled = 1 AND amount_collected >= amount_due) OR
        (is_settled = 0 AND amount_collected < amount_due)
    )
);

CREATE INDEX idx_uncollected_acc ON uncollected_fees(account_id, is_settled);

-- 6. Table: interest_accruals
CREATE TABLE interest_accruals (
    accrual_id     VARCHAR2(64) PRIMARY KEY,
    account_id     VARCHAR2(64) NOT NULL,
    accrual_date   DATE NOT NULL,
    daily_rate     NUMBER(12, 8) NOT NULL,
    accrued_amount NUMBER(18, 4) NOT NULL,
    tax_withheld   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    net_accrual    NUMBER(18, 4) NOT NULL,
    is_capitalized NUMBER(1) DEFAULT 0 NOT NULL CHECK (is_capitalized IN (0, 1)),
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_int_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_net_accrual CHECK (net_accrual = accrued_amount - tax_withheld)
);

CREATE INDEX idx_int_acc_date ON interest_accruals(account_id, accrual_date);

-- 7. Table: eod_balance_snapshots
CREATE TABLE eod_balance_snapshots (
    snapshot_id     VARCHAR2(64) PRIMARY KEY,
    account_id      VARCHAR2(64) NOT NULL,
    business_date   DATE NOT NULL,
    closing_balance NUMBER(18, 4) NOT NULL,
    frozen_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_snap_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);

CREATE INDEX idx_eod_snap_date ON eod_balance_snapshots(business_date, account_id);

-- 8. Table: system_dates
CREATE TABLE system_dates (
    system_date_id        VARCHAR2(64) PRIMARY KEY,
    business_date         DATE NOT NULL,
    status                VARCHAR2(30) DEFAULT 'ONLINE' NOT NULL CHECK (status IN ('ONLINE', 'EOD_CUTOFF', 'COB_PROCESSING', 'ROLLOVER', 'ERROR_HALTED')),
    posting_window_open   NUMBER(1) DEFAULT 1 NOT NULL CHECK (posting_window_open IN (0, 1)),
    last_cob_completed_at TIMESTAMP WITH TIME ZONE,
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 9. Table: cob_batch_log
CREATE TABLE cob_batch_log (
    batch_id               VARCHAR2(64) PRIMARY KEY,
    business_date          DATE NOT NULL,
    started_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at           TIMESTAMP WITH TIME ZONE,
    status                 VARCHAR2(20) DEFAULT 'RUNNING' NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    current_phase          VARCHAR2(50),
    accounts_processed     NUMBER(10) DEFAULT 0 NOT NULL,
    total_fees_collected   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    total_interest_accrued NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    total_tax_withheld     NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    error_message          VARCHAR2(1000)
);

CREATE INDEX idx_cob_log_date ON cob_batch_log(business_date);

-- 10. Table: transaction_status_history
CREATE TABLE transaction_status_history (
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
);

CREATE INDEX idx_tsh_tx ON transaction_status_history(transaction_id, changed_at ASC);

-- ==============================================================================
-- Idempotent Foundational Reference Data (Chart of Accounts & System Dates)
-- Note: Uses MERGE INTO to prevent duplicate record insertion or PK collisions.
-- ==============================================================================

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
    SELECT 'SYS-DATE-1' AS system_date_id, TRUNC(CURRENT_DATE) AS business_date, 'ONLINE' AS status, 1 AS posting_window_open FROM dual
) source
ON (target.system_date_id = source.system_date_id)
WHEN NOT MATCHED THEN
    INSERT (system_date_id, business_date, status, posting_window_open, updated_at)
    VALUES (source.system_date_id, source.business_date, source.status, source.posting_window_open, CURRENT_TIMESTAMP);
