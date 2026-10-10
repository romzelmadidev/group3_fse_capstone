-- ==============================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- Epic B: Database & Schema Design (Oracle XE 21c Master Database)
-- File: 01_create_tables_and_constraints.sql
-- Description: Definitive Oracle XE 21c Schema Definition matching the
--              official Group 3 Engineering Repository Source of Truth.
-- ==============================================================================

SET ECHO ON;
SET FEEDBACK ON;
SET SQLBLANKLINES ON;

-- ------------------------------------------------------------------------------
-- 1. USERS TABLE
-- Master profile for Customers, Tellers, and Admins.
-- ------------------------------------------------------------------------------
CREATE TABLE users (
    user_id                 VARCHAR2(64) PRIMARY KEY,
    first_name              VARCHAR2(100) NOT NULL,
    middle_name             VARCHAR2(100),
    last_name               VARCHAR2(100) NOT NULL,
    email                   VARCHAR2(255) NOT NULL UNIQUE,
    phone_number            VARCHAR2(30) NOT NULL UNIQUE,
    dob                     DATE NOT NULL,
    government_id           VARCHAR2(100) NOT NULL,
    role                    VARCHAR2(20) NOT NULL CHECK (role IN ('CUSTOMER', 'TELLER', 'MANAGER', 'ADMIN')),
    password_hash           VARCHAR2(255) NOT NULL,
    pin_hash                VARCHAR2(255),
    max_concurrent_sessions NUMBER(3) DEFAULT 3 NOT NULL,
    failed_login_attempts   NUMBER(3) DEFAULT 0 NOT NULL,
    status                  VARCHAR2(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED')),
    last_known_latitude     NUMBER(10, 6) DEFAULT 14.5995,
    last_known_longitude    NUMBER(10, 6) DEFAULT 120.9842,
    last_known_location_name VARCHAR2(100) DEFAULT 'Manila, Philippines',
    last_known_ip           VARCHAR2(45) DEFAULT '112.198.45.10',
    last_geo_updated_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE users IS 'Master table for all system users (Customers, Tellers, Admins)';
COMMENT ON COLUMN users.user_id IS 'Unique identifier (UUID or business key like U1001)';
COMMENT ON COLUMN users.government_id IS 'Valid government issued ID (Passport, UMID, Drivers License)';
COMMENT ON COLUMN users.failed_login_attempts IS 'Failed login counter. Account is locked when reaching 5';


-- ------------------------------------------------------------------------------
-- 2. ACCOUNTS TABLE
-- Customer bank accounts (Savings only).
-- ------------------------------------------------------------------------------
CREATE TABLE accounts (
    account_id     VARCHAR2(64) PRIMARY KEY,
    user_id        VARCHAR2(64) NOT NULL,
    account_number VARCHAR2(32) NOT NULL UNIQUE,
    account_type   VARCHAR2(20) NOT NULL CHECK (account_type IN ('SAVINGS')),
    currency       VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    status         VARCHAR2(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_APPROVAL')),
    credit_limit   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL CHECK (credit_limit >= 0),
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_acc_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

COMMENT ON TABLE accounts IS 'Stores individual customer bank account records';
COMMENT ON COLUMN accounts.account_number IS 'Official formatted bank account number';
COMMENT ON COLUMN accounts.credit_limit IS 'Approved credit limit for CREDIT accounts; 0 for standard deposit accounts';


-- ------------------------------------------------------------------------------
-- 3. BALANCE_MASTER TABLE (Pessimistic Locking Target)
-- Dedicated table for live balances locked via SELECT ... FOR UPDATE.
-- Uses surrogate balance_id primary key to eliminate shared PK/FK anti-pattern.
-- ------------------------------------------------------------------------------
CREATE TABLE balance_master (
    balance_id        VARCHAR2(64) PRIMARY KEY,
    account_id        VARCHAR2(64) NOT NULL UNIQUE,
    balance_amount    NUMBER(18, 4) DEFAULT 0.0000 NOT NULL CHECK (balance_amount >= 0),
    hold_amount       NUMBER(18, 4) DEFAULT 0.0000 NOT NULL CHECK (hold_amount >= 0),
    available_balance NUMBER(18, 4) GENERATED ALWAYS AS (balance_amount - hold_amount) VIRTUAL,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_bm_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_bm_solvency CHECK (balance_amount >= hold_amount)
);

COMMENT ON TABLE balance_master IS 'Master balance records optimized for SELECT FOR UPDATE pessimistic locking';
COMMENT ON COLUMN balance_master.balance_id IS 'Surrogate primary key (e.g. BM-A2001) ensuring clean PK/FK separation';
COMMENT ON COLUMN balance_master.account_id IS 'Unique foreign key referencing accounts(account_id)';
COMMENT ON COLUMN balance_master.balance_amount IS 'Total ledger balance with 4 decimal places precision (@Digits(14,4))';
COMMENT ON COLUMN balance_master.hold_amount IS 'Funds frozen for pending approvals (Maker-Checker threshold > 100,000)';
COMMENT ON COLUMN balance_master.available_balance IS 'Spendable balance: (balance_amount - hold_amount) virtual generated column';

-- Trigger to maintain updated_at timestamp on balance_master updates
CREATE OR REPLACE TRIGGER trg_balance_master_updated_at
BEFORE UPDATE ON balance_master
FOR EACH ROW
BEGIN
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/


-- ------------------------------------------------------------------------------
-- 4. TRANSACTIONS TABLE
-- Operational transaction ledger for balance mutations.
-- Uses distributed idempotency key (transaction_id) to eliminate duplicate debits.
-- ------------------------------------------------------------------------------
CREATE TABLE transactions (
    transaction_id         VARCHAR2(64) PRIMARY KEY,
    from_account_id        VARCHAR2(64) NOT NULL,
    to_account_id          VARCHAR2(64),
    type                   VARCHAR2(30) NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    currency               VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    amount                 NUMBER(18, 4) NOT NULL CHECK (amount > 0),
    before_balance         NUMBER(18, 4) NOT NULL,
    after_balance          NUMBER(18, 4) NOT NULL,
    status                 VARCHAR2(30) NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'COMMITTED', 'FAILED')),
    requires_maker_checker NUMBER(1) DEFAULT 0 NOT NULL CHECK (requires_maker_checker IN (0, 1)),
    approved_by_user_id    VARCHAR2(64),
    idempotency_key        VARCHAR2(64) UNIQUE,
    memo                   VARCHAR2(255),
    created_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_tx_from_acc FOREIGN KEY (from_account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_tx_to_acc FOREIGN KEY (to_account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_tx_approver FOREIGN KEY (approved_by_user_id) REFERENCES users(user_id)
);

COMMENT ON TABLE transactions IS 'Operational transaction log for all deposits, withdrawals, and fund transfers';
COMMENT ON COLUMN transactions.transaction_id IS 'Idempotency key generated by client/gateway to eliminate duplicate executions';
COMMENT ON COLUMN transactions.requires_maker_checker IS 'Flag (1=True, 0=False) indicating high-value transfer (> PHP 50,000) requiring customer email OTP verification';


-- ------------------------------------------------------------------------------
-- 5. OUTBOX_EVENTS TABLE
-- Transactional Outbox Pattern for reliable Kafka Event Bus delivery.
-- ------------------------------------------------------------------------------
CREATE TABLE outbox_events (
    event_id       VARCHAR2(64) PRIMARY KEY,
    aggregate_type VARCHAR2(50) NOT NULL,
    aggregate_id   VARCHAR2(64) NOT NULL,
    event_type     VARCHAR2(50) NOT NULL,
    kafka_topic    VARCHAR2(100) NOT NULL,
    payload        CLOB NOT NULL,
    status         VARCHAR2(20) DEFAULT 'PENDING' NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    retry_count    NUMBER(4) DEFAULT 0 NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE
);

COMMENT ON TABLE outbox_events IS 'Transactional outbox table for reliable asynchronous event delivery to Kafka';
COMMENT ON COLUMN outbox_events.payload IS 'Complete JSON serialized payload dispatched to messaging brokers';


-- ------------------------------------------------------------------------------
-- 6. NOTIFICATIONS TABLE
-- Stores in-app alerts and notifications sent to users.
-- ------------------------------------------------------------------------------
CREATE TABLE notifications (
    notification_id VARCHAR2(64) PRIMARY KEY,
    user_id         VARCHAR2(64) NOT NULL,
    type            VARCHAR2(50) NOT NULL CHECK (type IN ('TRANSACTION_ALERT', 'SECURITY_ALERT', 'CUSTOMER_VERIFICATION_ALERT', 'MAKER_CHECKER_ALERT', 'AMLA_CTR_ALERT')),
    message         CLOB NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

COMMENT ON TABLE notifications IS 'User alerts for transaction confirmations and security notices';

-- ------------------------------------------------------------------------------
-- 7. GL_ACCOUNTS TABLE (Chart of Accounts)
-- ------------------------------------------------------------------------------
CREATE TABLE gl_accounts (
    gl_code      VARCHAR2(32) PRIMARY KEY,
    account_name VARCHAR2(100) NOT NULL,
    account_type VARCHAR2(20) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency     VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    is_active    NUMBER(1) DEFAULT 1 NOT NULL CHECK (is_active IN (0, 1))
);

-- ------------------------------------------------------------------------------
-- 8. GL_LEDGER TABLE (Double-Entry Journal Postings)
-- ------------------------------------------------------------------------------
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

-- ------------------------------------------------------------------------------
-- 9. GL_BALANCES TABLE (Real-Time Debit/Credit Accumulators)
-- ------------------------------------------------------------------------------
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

-- ------------------------------------------------------------------------------
-- 10. REVERSAL_REQUESTS TABLE (Maker-Checker Dispute Tickets)
-- ------------------------------------------------------------------------------
CREATE TABLE reversal_requests (
    ticket_id             VARCHAR2(64) PRIMARY KEY,
    original_tx_id        VARCHAR2(64) NOT NULL,
    maker_id              VARCHAR2(64) NOT NULL,
    checker_id            VARCHAR2(64),
    dispute_reason        VARCHAR2(100) NOT NULL,
    maker_notes           VARCHAR2(500) NOT NULL,
    checker_notes         VARCHAR2(500),
    status                VARCHAR2(20) DEFAULT 'PENDING' NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reversal_tx_id        VARCHAR2(64),
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    resolved_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_rev_orig_tx FOREIGN KEY (original_tx_id) REFERENCES transactions(transaction_id)
);

CREATE INDEX idx_rev_orig_tx ON reversal_requests(original_tx_id);
CREATE INDEX idx_rev_status ON reversal_requests(status);

-- ------------------------------------------------------------------------------
-- 11. UNCOLLECTED_FEES TABLE (Zero-Overdraft Arrears Tracking)
-- ------------------------------------------------------------------------------
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

-- ------------------------------------------------------------------------------
-- 12. INTEREST_ACCRUALS TABLE (Daily Accrued Interest & BIR Withholding)
-- ------------------------------------------------------------------------------
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

-- ------------------------------------------------------------------------------
-- 13. EOD_BALANCE_SNAPSHOTS TABLE (Closing State Freezes)
-- ------------------------------------------------------------------------------
CREATE TABLE eod_balance_snapshots (
    snapshot_id     VARCHAR2(64) PRIMARY KEY,
    account_id      VARCHAR2(64) NOT NULL,
    business_date   DATE NOT NULL,
    closing_balance NUMBER(18, 4) NOT NULL,
    frozen_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_snap_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);

CREATE INDEX idx_eod_snap_date ON eod_balance_snapshots(business_date, account_id);

-- ------------------------------------------------------------------------------
-- 14. SYSTEM_DATES TABLE (Core Business Date & COB State Machine)
-- ------------------------------------------------------------------------------
CREATE TABLE system_dates (
    system_date_id        VARCHAR2(64) PRIMARY KEY,
    business_date         DATE NOT NULL,
    status                VARCHAR2(30) DEFAULT 'ONLINE' NOT NULL CHECK (status IN ('ONLINE', 'EOD_CUTOFF', 'COB_PROCESSING', 'ROLLOVER', 'ERROR_HALTED')),
    posting_window_open   NUMBER(1) DEFAULT 1 NOT NULL CHECK (posting_window_open IN (0, 1)),
    last_cob_completed_at TIMESTAMP WITH TIME ZONE,
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- ------------------------------------------------------------------------------
-- 15. COB_BATCH_LOG TABLE (Master COB Operational Audit Log)
-- ------------------------------------------------------------------------------
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

-- ------------------------------------------------------------------------------
-- 16. TRANSACTION_STATUS_HISTORY TABLE (Master Transition Log)
-- ------------------------------------------------------------------------------
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

-- ------------------------------------------------------------------------------
-- Baseline Seeds
-- ------------------------------------------------------------------------------
INSERT INTO gl_accounts (gl_code, account_name, account_type, currency, is_active)
VALUES ('1010-CASH-VAULT', 'Cash and Cash Equivalents Vault', 'ASSET', 'PHP', 1);

INSERT INTO gl_accounts (gl_code, account_name, account_type, currency, is_active)
VALUES ('2100-CUST-LIAB', 'Customer Deposit Liabilities (Subledger Control)', 'LIABILITY', 'PHP', 1);

INSERT INTO gl_accounts (gl_code, account_name, account_type, currency, is_active)
VALUES ('4010-FEE-INCOME', 'Fee and Commission Income', 'REVENUE', 'PHP', 1);

INSERT INTO gl_accounts (gl_code, account_name, account_type, currency, is_active)
VALUES ('5010-INT-EXPENSE', 'Deposit Interest Expense', 'EXPENSE', 'PHP', 1);

INSERT INTO gl_accounts (gl_code, account_name, account_type, currency, is_active)
VALUES ('2150-TAX-WITHHOLD-PAYABLE', 'BIR Final Withholding Tax Payable (20%)', 'LIABILITY', 'PHP', 1);

INSERT INTO gl_accounts (gl_code, account_name, account_type, currency, is_active)
VALUES ('20100', 'Demand Deposit - Customer Accounts', 'LIABILITY', 'PHP', 1);

INSERT INTO gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES ('1010-CASH-VAULT', '2026-10', 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

INSERT INTO gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES ('2100-CUST-LIAB', '2026-10', 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

INSERT INTO gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES ('4010-FEE-INCOME', '2026-10', 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

INSERT INTO gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES ('5010-INT-EXPENSE', '2026-10', 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

INSERT INTO gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES ('2150-TAX-WITHHOLD-PAYABLE', '2026-10', 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

INSERT INTO gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES ('20100', '2026-10', 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

INSERT INTO gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES ('20100', '2026-M10', 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

INSERT INTO system_dates (system_date_id, business_date, status, posting_window_open, last_cob_completed_at, updated_at)
VALUES ('SYS-DATE-1', TRUNC(CURRENT_DATE), 'ONLINE', 1, NULL, CURRENT_TIMESTAMP);


