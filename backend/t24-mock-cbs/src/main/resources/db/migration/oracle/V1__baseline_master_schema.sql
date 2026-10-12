-- ==============================================================================
-- Flyway Migration: V1__baseline_master_schema.sql
-- Engine: Oracle Database 21c / 23ai
-- Description: Baseline transactional tables for users, accounts, balances,
--              transactions, outbox, notifications, KYC, and device tokens.
-- Note: Pure declarative DDL without DROP statements or hardcoded test seed.
-- ==============================================================================

-- 1. Table: users
CREATE TABLE users (
    user_id                  VARCHAR2(64) PRIMARY KEY,
    first_name               VARCHAR2(100) NOT NULL,
    middle_name              VARCHAR2(100),
    last_name                VARCHAR2(100) NOT NULL,
    email                    VARCHAR2(255) NOT NULL UNIQUE,
    phone_number             VARCHAR2(30) NOT NULL UNIQUE,
    dob                      DATE NOT NULL,
    government_id            VARCHAR2(100) NOT NULL,
    role                     VARCHAR2(20) NOT NULL,
    password_hash            VARCHAR2(255) NOT NULL,
    pin_hash                 VARCHAR2(255),
    max_concurrent_sessions  NUMBER(3) DEFAULT 3 NOT NULL,
    failed_login_attempts    NUMBER(3) DEFAULT 0 NOT NULL,
    status                   VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    kyc_status               VARCHAR2(30) DEFAULT 'PENDING' NOT NULL,
    kyc_review_reason        VARCHAR2(500),
    last_known_latitude      NUMBER(10, 6) DEFAULT 14.5995,
    last_known_longitude     NUMBER(10, 6) DEFAULT 120.9842,
    last_known_location_name VARCHAR2(100) DEFAULT 'Manila, Philippines',
    last_known_ip            VARCHAR2(45)  DEFAULT '112.198.45.10',
    last_geo_updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login_at            TIMESTAMP WITH TIME ZONE,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_usr_role CHECK (role IN ('CUSTOMER', 'TELLER', 'ADMIN')),
    CONSTRAINT chk_usr_status CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED'))
);

-- 2. Table: accounts (Savings Accounts)
CREATE TABLE accounts (
    account_id     VARCHAR2(64) PRIMARY KEY,
    user_id        VARCHAR2(64) NOT NULL,
    account_number VARCHAR2(32) NOT NULL UNIQUE,
    account_type   VARCHAR2(20) NOT NULL,
    currency       VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    status         VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_acc_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT chk_acc_type CHECK (account_type IN ('SAVINGS')),
    CONSTRAINT chk_acc_status CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_APPROVAL'))
);

-- 3. Table: balance_master
CREATE TABLE balance_master (
    balance_id        VARCHAR2(64) PRIMARY KEY,
    account_id        VARCHAR2(64) NOT NULL UNIQUE,
    balance_amount    NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    hold_amount       NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    available_balance NUMBER(18, 4) GENERATED ALWAYS AS (balance_amount - hold_amount) VIRTUAL,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_bm_account FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_bm_positive_balance CHECK (balance_amount >= 0),
    CONSTRAINT chk_bm_positive_hold CHECK (hold_amount >= 0),
    CONSTRAINT chk_bm_available_balance CHECK (balance_amount >= hold_amount)
);

-- 4. Table: transactions
CREATE TABLE transactions (
    transaction_id         VARCHAR2(64) PRIMARY KEY,
    from_account_id        VARCHAR2(64),
    to_account_id          VARCHAR2(64),
    type                   VARCHAR2(30),
    transaction_type       VARCHAR2(30) DEFAULT 'INTRA_BANK',
    currency               VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    amount                 NUMBER(18, 4) NOT NULL,
    before_balance         NUMBER(18, 4) DEFAULT 0.0000,
    after_balance          NUMBER(18, 4) DEFAULT 0.0000,
    status                 VARCHAR2(30) NOT NULL,
    requires_2fa_otp       NUMBER(1) DEFAULT 0,
    requires_maker_checker NUMBER(1) DEFAULT 0,
    approved_by_user_id    VARCHAR2(64),
    approved_by            VARCHAR2(64),
    idempotency_key        VARCHAR2(64) UNIQUE,
    memo                   VARCHAR2(255),
    latitude               NUMBER(10, 6),
    longitude              NUMBER(10, 6),
    location_name          VARCHAR2(100),
    ip_address             VARCHAR2(45),
    risk_score             NUMBER(5, 2),
    risk_reason            VARCHAR2(255),
    reversed_by_user_id    VARCHAR2(64),
    reversal_reason        VARCHAR2(100),
    reversal_memo          VARCHAR2(255),
    created_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_tx_from_account FOREIGN KEY (from_account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_tx_to_account FOREIGN KEY (to_account_id) REFERENCES accounts(account_id),
    CONSTRAINT chk_tx_type CHECK (type IS NULL OR UPPER(type) IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL', 'INTRA_BANK', 'INTER_BANK')),
    CONSTRAINT chk_tx_status CHECK (UPPER(status) IN ('INITIATED', 'AUTHORIZED', 'RESERVED', 'PROCESSING', 'POSTED', 'FAILED', 'CANCELLED', 'PENDINGREVERSAL', 'PENDING_REVERSAL', 'REVERSED', 'PENDING_APPROVAL', 'COMMITTED', 'REJECTED_FRAUD')),
    CONSTRAINT chk_tx_2fa_otp CHECK (requires_2fa_otp IS NULL OR requires_2fa_otp IN (0, 1)),
    CONSTRAINT chk_tx_amount CHECK (amount > 0)
);

-- 5. Table: outbox_events
CREATE TABLE outbox_events (
    event_id       VARCHAR2(64) PRIMARY KEY,
    aggregate_type VARCHAR2(50) NOT NULL,
    aggregate_id   VARCHAR2(64) NOT NULL,
    event_type     VARCHAR2(50) NOT NULL,
    kafka_topic    VARCHAR2(100) NOT NULL,
    payload        CLOB NOT NULL,
    status         VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    retry_count    NUMBER(4) DEFAULT 0 NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_oe_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- 6. Table: notifications
CREATE TABLE notifications (
    notification_id VARCHAR2(64) PRIMARY KEY,
    user_id         VARCHAR2(64) NOT NULL,
    type            VARCHAR2(50) NOT NULL,
    message         CLOB NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT chk_notif_type CHECK (type IN ('TRANSACTION_ALERT', 'SECURITY_ALERT', 'CUSTOMER_VERIFICATION_ALERT', 'AMLA_CTR_ALERT', 'DEVICE_APPROVED', 'DEVICE_REVOKED', 'LOGIN_OTP'))
);

-- 7. Table: kyc_submissions
CREATE TABLE kyc_submissions (
    submission_id     VARCHAR2(64) PRIMARY KEY,
    user_id           VARCHAR2(64) NOT NULL,
    id_type           VARCHAR2(40) NOT NULL,
    front_blob_path   VARCHAR2(255) NOT NULL,
    back_blob_path    VARCHAR2(255),
    selfie_blob_path  VARCHAR2(255) NOT NULL,
    laya_decision     VARCHAR2(20) NOT NULL,
    confidence_score  NUMBER(5, 2) NOT NULL,
    face_similarity   NUMBER(5, 4),
    liveness_score    NUMBER(5, 4),
    ocr_confidence    NUMBER(5, 4),
    ocr_full_name     VARCHAR2(200),
    ocr_dob           VARCHAR2(20),
    ocr_id_number     VARCHAR2(60),
    laya_flags        VARCHAR2(1000),
    laya_summary      VARCHAR2(2000) NOT NULL,
    status            VARCHAR2(20) DEFAULT 'PENDING_MAKER' NOT NULL,
    maker_id          VARCHAR2(64),
    maker_decision    VARCHAR2(10),
    maker_note        VARCHAR2(500),
    maker_at          TIMESTAMP WITH TIME ZONE,
    checker_id        VARCHAR2(64),
    checker_note      VARCHAR2(500),
    checker_at        TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_kyc_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT chk_kyc_laya CHECK (laya_decision IN ('APPROVED', 'PENDING_REVIEW', 'REJECTED')),
    CONSTRAINT chk_kyc_status CHECK (status IN ('PENDING_MAKER', 'PENDING_CHECKER', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_kyc_maker_decision CHECK (maker_decision IN ('APPROVE', 'REJECT')),
    CONSTRAINT chk_kyc_four_eyes CHECK (checker_id IS NULL OR checker_id <> maker_id)
);

-- 8. Table: device_push_tokens
CREATE TABLE device_push_tokens (
    device_id     VARCHAR2(128) PRIMARY KEY,
    user_id       VARCHAR2(64) NOT NULL,
    push_token    VARCHAR2(512) NOT NULL,
    platform      VARCHAR2(20) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_push_platform CHECK (platform IN ('ANDROID', 'IOS', 'WEB'))
);

-- Performance Indexes
CREATE INDEX idx_acc_user ON accounts(user_id);
CREATE INDEX idx_tx_from_acc ON transactions(from_account_id, created_at DESC);
CREATE INDEX idx_tx_to_acc ON transactions(to_account_id, created_at DESC);
CREATE INDEX idx_tx_status ON transactions(status);
CREATE INDEX idx_outbox_status ON outbox_events(status, created_at);
CREATE INDEX idx_notif_user ON notifications(user_id, sent_at DESC);
CREATE INDEX idx_kyc_status ON kyc_submissions(status, created_at);
CREATE INDEX idx_kyc_user ON kyc_submissions(user_id, created_at DESC);
CREATE INDEX idx_push_user ON device_push_tokens(user_id);
