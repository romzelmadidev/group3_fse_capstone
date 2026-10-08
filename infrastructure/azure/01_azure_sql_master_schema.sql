-- ==============================================================================
-- FSE Capstone: Master Operational Database Initialization Script
-- Engine: Azure SQL Database (T-SQL)
-- Target Database: sqldb-master
-- Replaces legacy local Oracle XE 21c (XEPDB1)
-- ==============================================================================

-- Drop existing tables in reverse dependency order
IF OBJECT_ID('dbo.outbox_events', 'U') IS NOT NULL DROP TABLE dbo.outbox_events;
IF OBJECT_ID('dbo.notifications', 'U') IS NOT NULL DROP TABLE dbo.notifications;
IF OBJECT_ID('dbo.transactions', 'U') IS NOT NULL DROP TABLE dbo.transactions;
IF OBJECT_ID('dbo.balance_master', 'U') IS NOT NULL DROP TABLE dbo.balance_master;
IF OBJECT_ID('dbo.accounts', 'U') IS NOT NULL DROP TABLE dbo.accounts;
IF OBJECT_ID('dbo.users', 'U') IS NOT NULL DROP TABLE dbo.users;

-- ==============================================================================
-- 1. Table: users
-- ==============================================================================
CREATE TABLE dbo.users (
    user_id                 NVARCHAR(64) NOT NULL PRIMARY KEY,
    first_name              NVARCHAR(100) NOT NULL,
    middle_name             NVARCHAR(100) NULL,
    last_name               NVARCHAR(100) NOT NULL,
    email                   NVARCHAR(255) NOT NULL UNIQUE,
    phone_number            NVARCHAR(30) NOT NULL UNIQUE,
    dob                     DATE NOT NULL,
    government_id           NVARCHAR(100) NOT NULL,
    role                    NVARCHAR(20) NOT NULL,
    password_hash           NVARCHAR(255) NOT NULL,
    pin_hash                NVARCHAR(255) NULL,
    max_concurrent_sessions SMALLINT DEFAULT 3 NOT NULL,
    failed_login_attempts   SMALLINT DEFAULT 0 NOT NULL,
    status                  NVARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    created_at              DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    updated_at              DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT chk_usr_role CHECK (role IN ('CUSTOMER', 'TELLER', 'MANAGER', 'ADMIN')),
    CONSTRAINT chk_usr_status CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED'))
);

-- ==============================================================================
-- 2. Table: accounts
-- ==============================================================================
CREATE TABLE dbo.accounts (
    account_id     NVARCHAR(64) NOT NULL PRIMARY KEY,
    user_id        NVARCHAR(64) NOT NULL,
    account_number NVARCHAR(32) NOT NULL UNIQUE,
    account_type   NVARCHAR(20) NOT NULL,
    status         NVARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    credit_limit   DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    created_at     DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    updated_at     DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_acc_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT chk_acc_type CHECK (account_type IN ('SAVINGS', 'CHECKING')),
    CONSTRAINT chk_acc_status CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_APPROVAL')),
    CONSTRAINT chk_acc_credit_limit CHECK (credit_limit >= 0)
);

-- ==============================================================================
-- 3. Table: balance_master
-- Strict numeric parameters: DECIMAL(18, 4) with mathematical sanity checks
-- Concurrency target for: SELECT ... WITH (UPDLOCK, ROWLOCK)
-- ==============================================================================
CREATE TABLE dbo.balance_master (
    account_id        NVARCHAR(64) NOT NULL PRIMARY KEY,
    balance_amount    DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    hold_amount       DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    available_balance DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    created_at        DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    updated_at        DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_bm_account FOREIGN KEY (account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT chk_bm_positive_balance CHECK (balance_amount >= 0),
    CONSTRAINT chk_bm_positive_hold CHECK (hold_amount >= 0),
    CONSTRAINT chk_bm_available_balance CHECK (balance_amount >= hold_amount)
);

-- ==============================================================================
-- 4. Table: transactions
-- Maker-checker flag indicates transfers > PHP 50,000.00 requiring 2FA OTP
-- ==============================================================================
CREATE TABLE dbo.transactions (
    transaction_id         NVARCHAR(64) NOT NULL PRIMARY KEY,
    source_account_id      NVARCHAR(64) NOT NULL,
    target_account_id      NVARCHAR(64) NOT NULL,
    amount                 DECIMAL(18, 4) NOT NULL,
    currency               NVARCHAR(3) DEFAULT 'PHP' NOT NULL,
    transaction_type       NVARCHAR(20) NOT NULL,
    status                 NVARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    requires_maker_checker BIT DEFAULT 0 NOT NULL,
    approved_by            NVARCHAR(64) NULL,
    memo                   NVARCHAR(255) NULL,
    idempotency_key        NVARCHAR(64) NULL,
    created_at             DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    updated_at             DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_tx_source FOREIGN KEY (source_account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT fk_tx_target FOREIGN KEY (target_account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT chk_tx_amount CHECK (amount > 0)
);

-- ==============================================================================
-- 5. Table: outbox_events (Transactional Outbox Pattern for Azure Event Hubs)
-- ==============================================================================
CREATE TABLE dbo.outbox_events (
    event_id        NVARCHAR(64) NOT NULL PRIMARY KEY,
    aggregate_type  NVARCHAR(64) NOT NULL,
    aggregate_id    NVARCHAR(64) NOT NULL,
    event_type      NVARCHAR(64) NOT NULL,
    payload         NVARCHAR(MAX) NOT NULL,
    status          NVARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    retry_count     INT DEFAULT 0 NOT NULL,
    created_at      DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    processed_at    DATETIMEOFFSET NULL
);

-- ==============================================================================
-- 6. Table: notifications
-- ==============================================================================
CREATE TABLE dbo.notifications (
    notification_id   NVARCHAR(64) NOT NULL PRIMARY KEY,
    user_id           NVARCHAR(64) NOT NULL,
    recipient_address NVARCHAR(255) NOT NULL,
    notification_type NVARCHAR(30) NOT NULL,
    channel           NVARCHAR(20) DEFAULT 'EMAIL' NOT NULL,
    subject           NVARCHAR(255) NOT NULL,
    content_payload   NVARCHAR(MAX) NOT NULL,
    dispatch_status   NVARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    delivery_attempts INT DEFAULT 0 NOT NULL,
    created_at        DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    dispatched_at     DATETIMEOFFSET NULL,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id)
);

-- Performance Indexes
CREATE NONCLUSTERED INDEX idx_acc_user_id ON dbo.accounts(user_id);
CREATE NONCLUSTERED INDEX idx_tx_source ON dbo.transactions(source_account_id, created_at DESC);
CREATE NONCLUSTERED INDEX idx_tx_target ON dbo.transactions(target_account_id, created_at DESC);
CREATE UNIQUE NONCLUSTERED INDEX uq_tx_idempotency_key ON dbo.transactions(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE NONCLUSTERED INDEX idx_outbox_status ON dbo.outbox_events(status, created_at) WHERE status = 'PENDING';

-- ==============================================================================
<<<<<<< HEAD
-- 7. Table: gl_accounts (Chart of Accounts)
-- ==============================================================================
CREATE TABLE dbo.gl_accounts (
    gl_code      NVARCHAR(32) NOT NULL PRIMARY KEY,
    account_name NVARCHAR(100) NOT NULL,
    account_type NVARCHAR(20) NOT NULL,
    currency     NVARCHAR(3) DEFAULT 'PHP' NOT NULL,
    is_active    BIT DEFAULT 1 NOT NULL,
    CONSTRAINT chk_gl_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);

-- ==============================================================================
-- 8. Table: gl_ledger (Double-Entry Journal Postings)
-- ==============================================================================
CREATE TABLE dbo.gl_ledger (
    journal_id     NVARCHAR(64) NOT NULL PRIMARY KEY,
    transaction_id NVARCHAR(64) NOT NULL,
    gl_code        NVARCHAR(32) NOT NULL,
    debit_amount   DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    credit_amount  DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    posting_date   DATE NOT NULL,
    created_at     DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_gl_account FOREIGN KEY (gl_code) REFERENCES dbo.gl_accounts(gl_code),
    CONSTRAINT chk_gl_amounts CHECK (debit_amount >= 0 AND credit_amount >= 0)
);

CREATE NONCLUSTERED INDEX idx_gl_ledger_tx ON dbo.gl_ledger(transaction_id);
CREATE NONCLUSTERED INDEX idx_gl_ledger_date ON dbo.gl_ledger(posting_date, gl_code);

-- ==============================================================================
-- 9. Table: gl_balances (Real-Time Debit/Credit Accumulators)
-- ==============================================================================
CREATE TABLE dbo.gl_balances (
    gl_code       NVARCHAR(32) NOT NULL PRIMARY KEY,
    fiscal_period NVARCHAR(20) NOT NULL,
    total_debit   DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    total_credit  DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    net_balance   DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    updated_at    DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_glb_account FOREIGN KEY (gl_code) REFERENCES dbo.gl_accounts(gl_code)
);

-- ==============================================================================
-- 10. Table: reversal_requests (Maker-Checker Dispute Tickets)
-- ==============================================================================
CREATE TABLE dbo.reversal_requests (
    ticket_id             NVARCHAR(64) NOT NULL PRIMARY KEY,
    original_tx_id        NVARCHAR(64) NOT NULL,
    maker_id              NVARCHAR(64) NOT NULL,
    checker_id            NVARCHAR(64) NULL,
    dispute_reason        NVARCHAR(100) NOT NULL,
    maker_notes           NVARCHAR(500) NOT NULL,
    checker_notes         NVARCHAR(500) NULL,
    status                NVARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    reversal_tx_id        NVARCHAR(64) NULL,
    created_at            DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    resolved_at           DATETIMEOFFSET NULL,
    CONSTRAINT fk_rev_orig_tx FOREIGN KEY (original_tx_id) REFERENCES dbo.transactions(transaction_id),
    CONSTRAINT chk_rev_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE NONCLUSTERED INDEX idx_rev_orig_tx ON dbo.reversal_requests(original_tx_id);
CREATE NONCLUSTERED INDEX idx_rev_status ON dbo.reversal_requests(status);

-- ==============================================================================
-- 10B. Table: amount_holds (Temenos AC.LOCKED.EVENTS Reservations)
-- ==============================================================================
CREATE TABLE dbo.amount_holds (
    hold_id            NVARCHAR(64) NOT NULL PRIMARY KEY,
    account_id         NVARCHAR(64) NOT NULL,
    hold_amount        DECIMAL(18, 4) NOT NULL,
    reason             NVARCHAR(100) NOT NULL,
    status             NVARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    t24_lock_reference NVARCHAR(64) NULL,
    external_reference NVARCHAR(100) NULL,
    expires_at         DATETIMEOFFSET NULL,
    created_at         DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    released_at        DATETIMEOFFSET NULL,
    CONSTRAINT fk_hold_acc FOREIGN KEY (account_id) REFERENCES dbo.accounts(account_id),
    CONSTRAINT chk_hold_amt CHECK (hold_amount > 0),
    CONSTRAINT chk_hold_status CHECK (status IN ('ACTIVE', 'RELEASED', 'CAPTURED'))
);

CREATE NONCLUSTERED INDEX idx_hold_acc ON dbo.amount_holds(account_id, status);


-- ==============================================================================
-- 11. Table: uncollected_fees (Zero-Overdraft Arrears Tracking)
-- ==============================================================================
CREATE TABLE dbo.uncollected_fees (
    fee_id           NVARCHAR(64) NOT NULL PRIMARY KEY,
    account_id       NVARCHAR(64) NOT NULL,
    fee_type         NVARCHAR(50) NOT NULL,
    amount_due       DECIMAL(18, 4) NOT NULL,
    amount_collected DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    is_settled       BIT DEFAULT 0 NOT NULL,
    created_at       DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_uncol_acc FOREIGN KEY (account_id) REFERENCES dbo.accounts(account_id)
);

CREATE NONCLUSTERED INDEX idx_uncollected_acc ON dbo.uncollected_fees(account_id, is_settled);

-- ==============================================================================
-- 12. Table: interest_accruals (Daily Accrued Interest & BIR Withholding)
-- ==============================================================================
CREATE TABLE dbo.interest_accruals (
    accrual_id     NVARCHAR(64) NOT NULL PRIMARY KEY,
    account_id     NVARCHAR(64) NOT NULL,
    accrual_date   DATE NOT NULL,
    daily_rate     DECIMAL(12, 8) NOT NULL,
    accrued_amount DECIMAL(18, 4) NOT NULL,
    tax_withheld   DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    net_accrual    DECIMAL(18, 4) NOT NULL,
    is_capitalized BIT DEFAULT 0 NOT NULL,
    created_at     DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_int_acc FOREIGN KEY (account_id) REFERENCES dbo.accounts(account_id)
);

CREATE NONCLUSTERED INDEX idx_int_acc_date ON dbo.interest_accruals(account_id, accrual_date);

-- ==============================================================================
-- 13. Table: eod_balance_snapshots (Closing State Freezes)
-- ==============================================================================
CREATE TABLE dbo.eod_balance_snapshots (
    snapshot_id     NVARCHAR(64) NOT NULL PRIMARY KEY,
    account_id      NVARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    closing_balance DECIMAL(18, 4) NOT NULL,
    frozen_at       DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT fk_snap_acc FOREIGN KEY (account_id) REFERENCES dbo.accounts(account_id)
);

CREATE NONCLUSTERED INDEX idx_eod_snap_date ON dbo.eod_balance_snapshots(business_date, account_id);

-- ==============================================================================
-- 14. Table: system_dates (Core Business Date & COB State Machine)
-- ==============================================================================
CREATE TABLE dbo.system_dates (
    system_date_id        NVARCHAR(64) NOT NULL PRIMARY KEY,
    business_date         DATE NOT NULL,
    status                NVARCHAR(30) DEFAULT 'ONLINE' NOT NULL,
    posting_window_open   BIT DEFAULT 1 NOT NULL,
    last_cob_completed_at DATETIMEOFFSET NULL,
    updated_at            DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    CONSTRAINT chk_sys_date_status CHECK (status IN ('ONLINE', 'EOD_CUTOFF', 'COB_PROCESSING', 'ROLLOVER', 'ERROR_HALTED'))
);

-- ==============================================================================
-- 15. Table: cob_batch_log (Master COB Operational Audit Log)
-- ==============================================================================
CREATE TABLE dbo.cob_batch_log (
    batch_id               NVARCHAR(64) NOT NULL PRIMARY KEY,
    business_date          DATE NOT NULL,
    started_at             DATETIMEOFFSET NOT NULL,
    completed_at           DATETIMEOFFSET NULL,
    status                 NVARCHAR(20) DEFAULT 'RUNNING' NOT NULL,
    current_phase          NVARCHAR(50) NULL,
    accounts_processed     INT DEFAULT 0 NOT NULL,
    total_fees_collected   DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    total_interest_accrued DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    total_tax_withheld     DECIMAL(18, 4) DEFAULT 0.0000 NOT NULL,
    error_message          NVARCHAR(1000) NULL,
    CONSTRAINT chk_cob_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE NONCLUSTERED INDEX idx_cob_log_date ON dbo.cob_batch_log(business_date);

-- ==============================================================================
-- 16. Table: transaction_status_history (Master Transition Log)
-- ==============================================================================
CREATE TABLE dbo.transaction_status_history (
    history_id     NVARCHAR(64) NOT NULL PRIMARY KEY,
    transaction_id NVARCHAR(64) NOT NULL,
    from_status    NVARCHAR(30) NULL,
    to_status      NVARCHAR(30) NOT NULL,
    change_reason  NVARCHAR(100) NOT NULL,
    reason_details NVARCHAR(500) NULL,
    actor_id       NVARCHAR(64) NOT NULL,
    actor_type     NVARCHAR(30) NOT NULL,
    changed_at     DATETIMEOFFSET DEFAULT SYSDATETIMEOFFSET() NOT NULL,
    metadata_json  NVARCHAR(MAX) NULL,
    CONSTRAINT fk_tsh_tx FOREIGN KEY (transaction_id) REFERENCES dbo.transactions(transaction_id)
);

CREATE NONCLUSTERED INDEX idx_tsh_tx ON dbo.transaction_status_history(transaction_id, changed_at ASC);

-- ==============================================================================
-- Seed Baseline Chart of Accounts & Initial System Date
-- ==============================================================================
INSERT INTO dbo.gl_accounts (gl_code, account_name, account_type, currency, is_active)
VALUES
    ('1010-CASH-VAULT', 'Cash and Cash Equivalents Vault', 'ASSET', 'PHP', 1),
    ('2100-CUST-LIAB', 'Customer Deposit Liabilities (Subledger Control)', 'LIABILITY', 'PHP', 1),
    ('4010-FEE-INCOME', 'Fee and Commission Income', 'REVENUE', 'PHP', 1),
    ('5010-INT-EXPENSE', 'Deposit Interest Expense', 'EXPENSE', 'PHP', 1),
    ('2150-TAX-WITHHOLD-PAYABLE', 'BIR Final Withholding Tax Payable (20%)', 'LIABILITY', 'PHP', 1);

INSERT INTO dbo.gl_balances (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
VALUES
    ('1010-CASH-VAULT', '2026-10', 0.0000, 0.0000, 0.0000, SYSDATETIMEOFFSET()),
    ('2100-CUST-LIAB', '2026-10', 0.0000, 0.0000, 0.0000, SYSDATETIMEOFFSET()),
    ('4010-FEE-INCOME', '2026-10', 0.0000, 0.0000, 0.0000, SYSDATETIMEOFFSET()),
    ('5010-INT-EXPENSE', '2026-10', 0.0000, 0.0000, 0.0000, SYSDATETIMEOFFSET()),
    ('2150-TAX-WITHHOLD-PAYABLE', '2026-10', 0.0000, 0.0000, 0.0000, SYSDATETIMEOFFSET());

INSERT INTO dbo.system_dates (system_date_id, business_date, status, posting_window_open, last_cob_completed_at, updated_at)
VALUES ('SYS-DATE-001', '2026-10-07', 'ONLINE', 1, NULL, SYSDATETIMEOFFSET());

-- ==============================================================================
-- Seed Baseline Data for Testing & Demonstration
-- ==============================================================================
INSERT INTO dbo.users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES 
('usr-1001-cst-001', 'Juan', 'Santos', 'Dela Cruz', 'juan.dc@email.com', '+639171234567', '1990-05-15', 'PASSPORT-P9876543A', 'CUSTOMER', '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE'),
('usr-1002-cst-002', 'Maria', 'Clara', 'Reyes', 'maria.reyes@eastwestbanker.com', '+639189876543', '1992-08-20', 'UMID-0111-2233445-6', 'CUSTOMER', '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE'),
('usr-1003-tel-001', 'Crisostomo', 'Alfonso', 'Ibarra', 'crisostomo.ibarra@eastwestbanker.com', '+639201112233', '1985-01-10', 'DRIVERS-LIC-N01-90-123456', 'TELLER', '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE'),
('usr-1004-adm-001', 'Diana', 'Core', 'Administrator', 'diana.admin@bank.com', '+639000000000', '1980-01-01', 'COMPANY-ID-EMP-001', 'ADMIN', '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', NULL, 10, 0, 'ACTIVE');

INSERT INTO dbo.accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES 
('1000-2000-3001', 'usr-1001-cst-001', '1000-2000-3001', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-2000-3002', 'usr-1002-cst-002', '1000-2000-3002', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-2000-3003', 'usr-1001-cst-001', '1000-2000-3003', 'CHECKING', 'ACTIVE', 0.0000),
('acc-2001-sav-001', 'usr-1001-cst-001', '100100001234', 'SAVINGS', 'ACTIVE', 0.0000),
('acc-2002-chk-001', 'usr-1001-cst-001', '100100005678', 'SAVINGS', 'ACTIVE', 0.0000),
('acc-2003-sav-002', 'usr-1002-cst-002', '100200009999', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO dbo.balance_master (account_id, balance_amount, hold_amount, available_balance)
VALUES 
('1000-2000-3001', 25000000.0000, 0.0000, 25000000.0000),
('1000-2000-3002', 5000000.0000, 0.0000, 5000000.0000),
('1000-2000-3003', 10000000.0000, 0.0000, 10000000.0000),
('acc-2001-sav-001', 25000000.0000, 5000000.0000, 20000000.0000),
('acc-2002-chk-001', 8500000.0000, 0.0000, 8500000.0000),
('acc-2003-sav-002', 12345678.1250, 0.0000, 12345678.1250);

