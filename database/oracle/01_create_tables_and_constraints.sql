-- ==============================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- Epic B: Database & Schema Design (Oracle XE 21c Master Database)
-- File: 01_create_tables_and_constraints.sql
-- Description: Creates master tables, primary keys, foreign keys, default values,
--              and check constraints for banking operations.
-- ==============================================================================

-- Enable SQL*Plus settings for clean script execution
SET ECHO ON;
SET FEEDBACK ON;
SET SQLBLANKLINES ON;

-- ------------------------------------------------------------------------------
-- 1. USERS TABLE
-- Stores bank users: Customers, Tellers, and Admins.
-- Includes authentication credentials, transaction PIN, and session/lock controls.
-- ------------------------------------------------------------------------------
CREATE TABLE users (
    user_id                  VARCHAR2(36)   NOT NULL,
    first_name               VARCHAR2(100)  NOT NULL,
    middle_name              VARCHAR2(100),
    last_name                VARCHAR2(100)  NOT NULL,
    email                    VARCHAR2(255)  NOT NULL,
    phone_number             VARCHAR2(30)   NOT NULL,
    dob                      DATE           NOT NULL,
    government_id            VARCHAR2(100)  NOT NULL,
    role                     VARCHAR2(20)   NOT NULL,
    password_hash            VARCHAR2(255)  NOT NULL,
    pin_hash                 VARCHAR2(255),
    max_concurrent_sessions  NUMBER(3)      DEFAULT 3 NOT NULL,
    failed_login_attempts    NUMBER(3)      DEFAULT 0 NOT NULL,
    status                   VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    created_at               TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at               TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_phone UNIQUE (phone_number),
    CONSTRAINT chk_users_role CHECK (role IN ('CUSTOMER', 'TELLER', 'ADMIN')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED')),
    CONSTRAINT chk_users_failed_attempts CHECK (failed_login_attempts >= 0),
    CONSTRAINT chk_users_max_sessions CHECK (max_concurrent_sessions > 0)
);

COMMENT ON TABLE users IS 'Master table for all system users (Customers, Tellers, Admins)';
COMMENT ON COLUMN users.user_id IS 'Unique identifier (UUID or business key like U1001)';
COMMENT ON COLUMN users.government_id IS 'Valid government issued ID (e.g., Passport, UMID, Drivers License)';
COMMENT ON COLUMN users.pin_hash IS 'BCrypt hash of the 6-digit transaction authorization PIN';
COMMENT ON COLUMN users.failed_login_attempts IS 'Failed login counter. Account is locked when reaching 5';


-- ------------------------------------------------------------------------------
-- 2. ACCOUNTS TABLE
-- Stores bank accounts linked to customer profiles.
-- Supports multiple account types (SAVINGS, CHECKING, CREDIT) per user.
-- ------------------------------------------------------------------------------
CREATE TABLE accounts (
    account_id       VARCHAR2(36)   NOT NULL,
    user_id          VARCHAR2(36)   NOT NULL,
    account_number   VARCHAR2(30)   NOT NULL,
    account_type     VARCHAR2(20)   NOT NULL,
    status           VARCHAR2(20)   DEFAULT 'ACTIVE' NOT NULL,
    created_at       TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_accounts PRIMARY KEY (account_id),
    CONSTRAINT uq_accounts_acc_num UNIQUE (account_number),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT chk_accounts_type CHECK (account_type IN ('SAVINGS', 'CHECKING', 'CREDIT')),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_APPROVAL', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE accounts IS 'Stores individual customer bank account records';
COMMENT ON COLUMN accounts.account_number IS 'Official formatted bank account number';


-- ------------------------------------------------------------------------------
-- 3. BALANCE_MASTER TABLE
-- Holds the live balance state for each account.
-- ARCHITECTURAL NOTE: Separating balance from account metadata enables high-speed
-- pessimistic locking (SELECT ... FOR UPDATE) during transaction mutations without
-- locking or blocking customer profile read operations.
-- ------------------------------------------------------------------------------
CREATE TABLE balance_master (
    account_id         VARCHAR2(36)   NOT NULL,
    balance_amount     NUMBER(18, 4)  DEFAULT 0.0000 NOT NULL,
    hold_amount        NUMBER(18, 4)  DEFAULT 0.0000 NOT NULL,
    available_balance  NUMBER(18, 4)  DEFAULT 0.0000 NOT NULL,
    created_at         TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_balance_master PRIMARY KEY (account_id),
    CONSTRAINT fk_balance_accounts FOREIGN KEY (account_id) REFERENCES accounts (account_id) ON DELETE CASCADE,
    CONSTRAINT chk_balance_hold_pos CHECK (hold_amount >= 0)
);

COMMENT ON TABLE balance_master IS 'Master balance records optimized for SELECT FOR UPDATE pessimistic locking';
COMMENT ON COLUMN balance_master.balance_amount IS 'Total ledger balance with 4 decimal places precision (@Digits(14,4))';
COMMENT ON COLUMN balance_master.hold_amount IS 'Funds frozen for pending approvals (Maker-Checker threshold > 100,000)';
COMMENT ON COLUMN balance_master.available_balance IS 'Liquid funds ready for withdrawal/mutation: (balance_amount - hold_amount)';

-- Trigger to automatically calculate and maintain available_balance
CREATE OR REPLACE TRIGGER trg_calc_available_balance
BEFORE INSERT OR UPDATE ON balance_master
FOR EACH ROW
BEGIN
    :NEW.available_balance := :NEW.balance_amount - :NEW.hold_amount;
    :NEW.updated_at := CURRENT_TIMESTAMP;
END;
/


-- ------------------------------------------------------------------------------
-- 4. CREDIT_ASSESSMENTS TABLE
-- Stores credit evaluations and collateral assessments conducted by Bank Tellers.
-- ------------------------------------------------------------------------------
CREATE TABLE credit_assessments (
    assessment_id               VARCHAR2(36)   NOT NULL,
    account_id                  VARCHAR2(36)   NOT NULL,
    user_id                     VARCHAR2(36)   NOT NULL,
    collateral_type             VARCHAR2(50)   NOT NULL,
    collateral_description      VARCHAR2(1000) NOT NULL,
    collateral_market_value     NUMBER(18, 4)  NOT NULL,
    collateral_appraised_value  NUMBER(18, 4)  NOT NULL,
    credit_score                NUMBER(4)      NOT NULL,
    approved_credit_limit       NUMBER(18, 4)  NOT NULL,
    risk_tier                   VARCHAR2(20)   NOT NULL,
    assessed_by_teller_id       VARCHAR2(36)   NOT NULL,
    status                      VARCHAR2(20)   DEFAULT 'PENDING' NOT NULL,
    created_at                  TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                  TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_credit_assessments PRIMARY KEY (assessment_id),
    CONSTRAINT fk_ca_account FOREIGN KEY (account_id) REFERENCES accounts (account_id),
    CONSTRAINT fk_ca_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_ca_teller FOREIGN KEY (assessed_by_teller_id) REFERENCES users (user_id),
    CONSTRAINT chk_ca_collateral_type CHECK (collateral_type IN ('REAL_ESTATE', 'VEHICLE', 'TIME_DEPOSIT', 'OTHER')),
    CONSTRAINT chk_ca_market_val CHECK (collateral_market_value >= 0),
    CONSTRAINT chk_ca_appraised_val CHECK (collateral_appraised_value >= 0),
    CONSTRAINT chk_ca_credit_score CHECK (credit_score BETWEEN 300 AND 850),
    CONSTRAINT chk_ca_approved_limit CHECK (approved_credit_limit >= 0),
    CONSTRAINT chk_ca_risk_tier CHECK (risk_tier IN ('LOW_RISK', 'MEDIUM_RISK', 'HIGH_RISK')),
    CONSTRAINT chk_ca_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

COMMENT ON TABLE credit_assessments IS 'Credit assessment evaluations conducted by bank staff for credit products';
COMMENT ON COLUMN credit_assessments.risk_tier IS 'Assigned customer risk category (LOW_RISK, MEDIUM_RISK, HIGH_RISK)';


-- ------------------------------------------------------------------------------
-- 5. TRANSACTIONS TABLE
-- Primary ledger transaction log for balance mutations.
-- Uses distributed idempotency key (transaction_id) to prevent duplicate processing.
-- ------------------------------------------------------------------------------
CREATE TABLE transactions (
    transaction_id          VARCHAR2(64)   NOT NULL,
    from_account_id         VARCHAR2(36)   NOT NULL,
    to_account_id           VARCHAR2(36),
    type                    VARCHAR2(30)   NOT NULL,
    amount                  NUMBER(18, 4)  NOT NULL,
    before_balance          NUMBER(18, 4)  NOT NULL,
    after_balance           NUMBER(18, 4)  NOT NULL,
    status                  VARCHAR2(30)   NOT NULL,
    requires_maker_checker  NUMBER(1)      DEFAULT 0 NOT NULL,
    approved_by_user_id     VARCHAR2(36),
    created_at              TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id),
    CONSTRAINT fk_txn_from_account FOREIGN KEY (from_account_id) REFERENCES accounts (account_id),
    CONSTRAINT fk_txn_to_account FOREIGN KEY (to_account_id) REFERENCES accounts (account_id),
    CONSTRAINT fk_txn_approved_by FOREIGN KEY (approved_by_user_id) REFERENCES users (user_id),
    CONSTRAINT chk_txn_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'CREDIT_DRAW')),
    CONSTRAINT chk_txn_amount_pos CHECK (amount > 0),
    CONSTRAINT chk_txn_status CHECK (status IN ('PENDING_APPROVAL', 'COMMITTED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT chk_txn_maker_checker CHECK (requires_maker_checker IN (0, 1))
);

COMMENT ON TABLE transactions IS 'Operational transaction log for all deposits, withdrawals, and fund transfers';
COMMENT ON COLUMN transactions.transaction_id IS 'Idempotency key generated by client/gateway to eliminate duplicate executions';
COMMENT ON COLUMN transactions.requires_maker_checker IS 'Flag (1=True, 0=False) indicating transaction requires checker/supervisor sign-off';


-- ------------------------------------------------------------------------------
-- 6. OUTBOX_EVENTS TABLE
-- Implements the Transactional Outbox Pattern.
-- Guarantees atomic database updates and event publishing to Kafka/RabbitMQ.
-- ------------------------------------------------------------------------------
CREATE TABLE outbox_events (
    event_id         VARCHAR2(64)   NOT NULL,
    aggregate_type   VARCHAR2(50)   NOT NULL,
    aggregate_id     VARCHAR2(64)   NOT NULL,
    event_type       VARCHAR2(50)   NOT NULL,
    kafka_topic      VARCHAR2(100)  NOT NULL,
    payload          CLOB           NOT NULL,
    status           VARCHAR2(20)   DEFAULT 'PENDING' NOT NULL,
    retry_count      NUMBER(3)      DEFAULT 0 NOT NULL,
    created_at       TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at     TIMESTAMP(6),
    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id),
    CONSTRAINT chk_outbox_aggregate_type CHECK (aggregate_type IN ('TRANSACTION', 'MAKER_CHECKER', 'BALANCE_MUTATION', 'ACCOUNT')),
    CONSTRAINT chk_outbox_event_type CHECK (event_type IN ('MAKER_PENDING', 'CHECKER_APPROVED', 'MUTATION_COMMITTED', 'MUTATION_FAILED')),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_outbox_retry_count CHECK (retry_count >= 0)
);

COMMENT ON TABLE outbox_events IS 'Transactional outbox table for reliable asynchronous event delivery';
COMMENT ON COLUMN outbox_events.payload IS 'Complete JSON serialized payload dispatched to messaging brokers';


-- ------------------------------------------------------------------------------
-- 7. NOTIFICATIONS TABLE
-- Stores in-app alerts and notifications sent to users.
-- ------------------------------------------------------------------------------
CREATE TABLE notifications (
    notification_id  VARCHAR2(64)   NOT NULL,
    user_id          VARCHAR2(36)   NOT NULL,
    type             VARCHAR2(50)   NOT NULL,
    message          VARCHAR2(1000) NOT NULL,
    read_status      NUMBER(1)      DEFAULT 0 NOT NULL,
    sent_at          TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at       TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (notification_id),
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT chk_notif_type CHECK (type IN ('TRANSACTION_ALERT', 'SECURITY_ALERT', 'MAKER_CHECKER_ALERT')),
    CONSTRAINT chk_notif_read_status CHECK (read_status IN (0, 1))
);

COMMENT ON TABLE notifications IS 'User alerts for transaction confirmations and security notices';


-- ------------------------------------------------------------------------------
-- 8. AUTH_SESSIONS TABLE
-- Supports stateless JWT tracking and Redis session eviction coordination.
-- ------------------------------------------------------------------------------
CREATE TABLE auth_sessions (
    session_id          VARCHAR2(64)   NOT NULL,
    user_id             VARCHAR2(36)   NOT NULL,
    device_fingerprint  VARCHAR2(255),
    is_revoked          NUMBER(1)      DEFAULT 0 NOT NULL,
    issued_at           TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at          TIMESTAMP(6)   NOT NULL,
    created_at          TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP(6)   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_auth_sessions PRIMARY KEY (session_id),
    CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT chk_sess_revoked CHECK (is_revoked IN (0, 1))
);

COMMENT ON TABLE auth_sessions IS 'Tracks active login sessions and token revocation states';
