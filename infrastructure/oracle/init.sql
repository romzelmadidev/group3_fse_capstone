-- =========================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- ORACLE XE 21c Initial DDL Schema & Starter Seed Data
-- =========================================================================

-- Connect to Pluggable Database
ALTER SESSION SET CONTAINER = FREEPDB1;

-- 1. USERS TABLE
CREATE TABLE users (
    user_id                 VARCHAR2(36)    PRIMARY KEY,
    first_name              VARCHAR2(50)    NOT NULL,
    middle_name             VARCHAR2(50),
    last_name               VARCHAR2(50)    NOT NULL,
    email                   VARCHAR2(100)   NOT NULL UNIQUE,
    phone_number            VARCHAR2(20)    NOT NULL UNIQUE,
    dob                     DATE            NOT NULL,
    government_id           VARCHAR2(50)    NOT NULL,
    role                    VARCHAR2(20)    NOT NULL CHECK (role IN ('CUSTOMER', 'TELLER', 'ADMIN')),
    password_hash           VARCHAR2(255)   NOT NULL,
    pin_hash                VARCHAR2(255)   NOT NULL,
    max_concurrent_sessions NUMBER(3)       DEFAULT 3 NOT NULL,
    failed_login_attempts   NUMBER(3)       DEFAULT 0 NOT NULL,
    status                  VARCHAR2(20)    DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED')),
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. ACCOUNTS TABLE
CREATE TABLE accounts (
    account_id              VARCHAR2(36)    PRIMARY KEY,
    user_id                 VARCHAR2(36)    NOT NULL,
    account_number          VARCHAR2(30)    NOT NULL UNIQUE,
    account_type            VARCHAR2(20)    NOT NULL CHECK (account_type IN ('SAVINGS', 'CHECKING', 'CREDIT')),
    status                  VARCHAR2(20)    DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_APPROVAL')),
    credit_limit            NUMBER(18, 4)   DEFAULT 0.0000 NOT NULL,
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_acc_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 3. BALANCE_MASTER TABLE (Row-locked via PESSIMISTIC_WRITE)
CREATE TABLE balance_master (
    account_id              VARCHAR2(36)    PRIMARY KEY,
    balance_amount          NUMBER(18, 4)   DEFAULT 0.0000 NOT NULL,
    hold_amount             NUMBER(18, 4)   DEFAULT 0.0000 NOT NULL,
    available_balance       NUMBER(18, 4)   GENERATED ALWAYS AS (balance_amount - hold_amount) VIRTUAL,
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_bal_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_positive_bal CHECK (balance_amount >= 0.0000),
    CONSTRAINT chk_positive_hold CHECK (hold_amount >= 0.0000)
);

-- 4. CREDIT_ASSESSMENTS TABLE (Collateral & Credit Scoring)
CREATE TABLE credit_assessments (
    assessment_id           VARCHAR2(36)    PRIMARY KEY,
    account_id              VARCHAR2(36)    NOT NULL,
    user_id                 VARCHAR2(36)    NOT NULL,
    collateral_type         VARCHAR2(30)    NOT NULL CHECK (collateral_type IN ('REAL_ESTATE', 'VEHICLE', 'TIME_DEPOSIT', 'SECURITIES')),
    collateral_description  VARCHAR2(500)   NOT NULL,
    collateral_market_value NUMBER(18, 4)   NOT NULL,
    collateral_appraised_value NUMBER(18, 4) NOT NULL,
    credit_score            NUMBER(5)       NOT NULL,
    approved_credit_limit   NUMBER(18, 4)   NOT NULL,
    risk_tier               VARCHAR2(20)    NOT NULL CHECK (risk_tier IN ('LOW_RISK', 'MEDIUM_RISK', 'HIGH_RISK')),
    assessed_by_teller_id   VARCHAR2(36)    NOT NULL,
    status                  VARCHAR2(20)    DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_ca_acc FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_ca_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_ca_teller FOREIGN KEY (assessed_by_teller_id) REFERENCES users(user_id)
);

-- 5. TRANSACTIONS TABLE
CREATE TABLE transactions (
    transaction_id          VARCHAR2(64)    PRIMARY KEY,
    from_account_id         VARCHAR2(36)    NOT NULL,
    to_account_id           VARCHAR2(36),
    type                    VARCHAR2(30)    NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'CREDIT_DRAW', 'CREDIT_PAYMENT')),
    amount                  NUMBER(18, 4)   NOT NULL,
    before_balance          NUMBER(18, 4)   NOT NULL,
    after_balance           NUMBER(18, 4)   NOT NULL,
    status                  VARCHAR2(20)    DEFAULT 'PENDING_APPROVAL' CHECK (status IN ('PENDING_APPROVAL', 'COMMITTED', 'FAILED')),
    requires_maker_checker  NUMBER(1)       DEFAULT 0 NOT NULL,
    approved_by_user_id     VARCHAR2(36),
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_tx_from FOREIGN KEY (from_account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_tx_to FOREIGN KEY (to_account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_tx_approver FOREIGN KEY (approved_by_user_id) REFERENCES users(user_id)
);

-- 6. OUTBOX_EVENTS TABLE (Transactional Outbox Pattern for Kafka Stream)
CREATE TABLE outbox_events (
    event_id                VARCHAR2(36)    PRIMARY KEY,
    aggregate_type          VARCHAR2(50)    NOT NULL,
    aggregate_id            VARCHAR2(64)    NOT NULL,
    event_type              VARCHAR2(50)    NOT NULL,
    kafka_topic             VARCHAR2(100)   NOT NULL,
    payload                 CLOB            NOT NULL,
    status                  VARCHAR2(20)    DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    retry_count             NUMBER(5)       DEFAULT 0 NOT NULL,
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at            TIMESTAMP
);

-- 7. NOTIFICATIONS TABLE
CREATE TABLE notifications (
    notification_id         VARCHAR2(36)    PRIMARY KEY,
    user_id                 VARCHAR2(36)    NOT NULL,
    type                    VARCHAR2(50)    NOT NULL,
    message                 VARCHAR2(500)   NOT NULL,
    read_status             NUMBER(1)       DEFAULT 0 NOT NULL,
    sent_at                 TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 8. AUTH_SESSIONS TABLE (Session Metadata - Tokens managed in Redis)
CREATE TABLE auth_sessions (
    session_id              VARCHAR2(36)    PRIMARY KEY,
    user_id                 VARCHAR2(36)    NOT NULL,
    device_fingerprint      VARCHAR2(100)   NOT NULL,
    is_revoked              NUMBER(1)       DEFAULT 0 NOT NULL,
    issued_at               TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at              TIMESTAMP       NOT NULL,
    created_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP       DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_sess_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Fast Indexing for High-Throughput Row Locking
CREATE INDEX idx_bm_acc ON balance_master(account_id);
CREATE INDEX idx_tx_status ON transactions(status);
CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at);

-- =========================================================================
-- STARTER SEED DATA
-- =========================================================================

-- Seed Users (Customer, Teller, Admin)
INSERT INTO users (user_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, password_hash, pin_hash)
VALUES ('U1001', 'Juan', 'Reyes', 'Dela Cruz', 'juan.dc@mail.com', '09171234567', TO_DATE('1990-05-14', 'YYYY-MM-DD'), 'PSA-1234-5678', 'CUSTOMER', '$2a$12$e8rQ9vXz7Y10abcdef1234567890abcdef1234567890abcdef12', '$2a$12$k4L9m1Wq2P80abcdef1234567890abcdef1234567890abcdef12');

INSERT INTO users (user_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, password_hash, pin_hash)
VALUES ('U3001', 'Alex', 'Mendoza', 'Reyes', 'alex.teller@bank.com', '09181112233', TO_DATE('1988-11-20', 'YYYY-MM-DD'), 'PRC-9988-7766', 'TELLER', '$2a$12$e8rQ9vXz7Y10abcdef1234567890abcdef1234567890abcdef12', '$2a$12$k4L9m1Wq2P80abcdef1234567890abcdef1234567890abcdef12');

INSERT INTO users (user_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, password_hash, pin_hash)
VALUES ('U4001', 'Maria', 'Santos', 'Ramos', 'maria.admin@bank.com', '09192223344', TO_DATE('1985-03-10', 'YYYY-MM-DD'), 'PASSPORT-P882910A', 'ADMIN', '$2a$12$e8rQ9vXz7Y10abcdef1234567890abcdef1234567890abcdef12', '$2a$12$k4L9m1Wq2P80abcdef1234567890abcdef1234567890abcdef12');

-- Seed Accounts (Juan's Savings and Credit Accounts)
INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('A2001', 'U1001', '1000-2000-3001', 'CREDIT', 'ACTIVE', 300000.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('A2002', 'U1001', '1000-2000-3002', 'SAVINGS', 'ACTIVE', 0.0000);

-- Seed Live Balances
INSERT INTO balance_master (account_id, balance_amount, hold_amount)
VALUES ('A2001', 298000.0000, 0.0000);

INSERT INTO balance_master (account_id, balance_amount, hold_amount)
VALUES ('A2002', 50000.0000, 0.0000);

-- Seed Collateral Evaluation
INSERT INTO credit_assessments (assessment_id, account_id, user_id, collateral_type, collateral_description, collateral_market_value, collateral_appraised_value, credit_score, approved_credit_limit, risk_tier, assessed_by_teller_id, status)
VALUES ('CA3001', 'A2001', 'U1001', 'VEHICLE', '2022 Toyota Vios 1.5G Automatic (Plate: ABC-1234)', 750000.0000, 600000.0000, 765, 300000.0000, 'LOW_RISK', 'U3001', 'APPROVED');

COMMIT;
