-- ==============================================================================
-- FSE Capstone: Master Operational Database Initialization Script
-- Engine: Oracle Database 21c / Free 23ai
-- Container: oracle-xe-master
-- Pluggable Database: XEPDB1
-- Application User: fse_user
-- ==============================================================================
-- Ensure table creation is clean and deterministic
WHENEVER SQLERROR CONTINUE;

-- Switch to pluggable database and application user schema if run by SYS
ALTER SESSION SET CONTAINER = XEPDB1;
ALTER SESSION SET CURRENT_SCHEMA = fse_user;

-- Drop existing tables in reverse dependency order
DROP TABLE outbox_events CASCADE CONSTRAINTS;
DROP TABLE notifications CASCADE CONSTRAINTS;
DROP TABLE auth_sessions CASCADE CONSTRAINTS;
DROP TABLE transactions CASCADE CONSTRAINTS;
DROP TABLE credit_assessments CASCADE CONSTRAINTS;
DROP TABLE balance_master CASCADE CONSTRAINTS;
DROP TABLE accounts CASCADE CONSTRAINTS;
DROP TABLE users CASCADE CONSTRAINTS;

-- Legacy cleanups if present
DROP TABLE customer_balance_master CASCADE CONSTRAINTS;
DROP TABLE bills_payment CASCADE CONSTRAINTS;
DROP TABLE transfers CASCADE CONSTRAINTS;
DROP TABLE user_roles CASCADE CONSTRAINTS;
DROP TABLE roles CASCADE CONSTRAINTS;

WHENEVER SQLERROR EXIT FAILURE;

-- ==============================================================================
-- 1. Table: users
-- ==============================================================================
CREATE TABLE users (
    user_id                 VARCHAR2(64) PRIMARY KEY,
    first_name              VARCHAR2(100) NOT NULL,
    middle_name             VARCHAR2(100),
    last_name               VARCHAR2(100) NOT NULL,
    email                   VARCHAR2(255) NOT NULL UNIQUE,
    phone_number            VARCHAR2(30) NOT NULL UNIQUE,
    dob                     DATE NOT NULL,
    government_id           VARCHAR2(100) NOT NULL,
    role                    VARCHAR2(20) NOT NULL,
    password_hash           VARCHAR2(255) NOT NULL,
    pin_hash                VARCHAR2(255),
    max_concurrent_sessions NUMBER(3) DEFAULT 3 NOT NULL,
    failed_login_attempts   NUMBER(3) DEFAULT 0 NOT NULL,
    status                  VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    kyc_status              VARCHAR2(30) DEFAULT 'PENDING' NOT NULL,
    kyc_review_reason       VARCHAR2(500),
    last_known_latitude     NUMBER(10, 6) DEFAULT 14.5995,
    last_known_longitude    NUMBER(10, 6) DEFAULT 120.9842,
    last_known_location_name VARCHAR2(100) DEFAULT 'Manila, Philippines',
    last_known_ip           VARCHAR2(45)  DEFAULT '112.198.45.10',
    last_geo_updated_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login_at           TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_usr_role CHECK (role IN ('CUSTOMER', 'TELLER', 'MANAGER', 'ADMIN')),
    CONSTRAINT chk_usr_status CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED'))
);

-- ==============================================================================
-- 2. Table: accounts
-- ==============================================================================
-- 2. Table: accounts (Savings Accounts Only)
-- ==============================================================================
CREATE TABLE accounts (
    account_id     VARCHAR2(64) PRIMARY KEY,
    user_id        VARCHAR2(64) NOT NULL,
    account_number VARCHAR2(32) NOT NULL UNIQUE,
    account_type   VARCHAR2(20) NOT NULL,
    currency       VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    status         VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    credit_limit   NUMBER(18, 4) DEFAULT 0.0000 NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_acc_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT chk_acc_type CHECK (account_type IN ('SAVINGS')),
    CONSTRAINT chk_acc_status CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_APPROVAL')),
    CONSTRAINT chk_acc_credit_limit CHECK (credit_limit >= 0)
);

-- ==============================================================================
-- 3. Table: balance_master
-- Strict numeric parameters: NUMBER(18, 4) with mathematical sanity checks
-- Surrogate balance_id PK eliminates shared-key anti-pattern
-- ==============================================================================
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

-- ==============================================================================
-- 4. Table: transactions
-- Note: requires_maker_checker indicates customer email verification required (> PHP 50,000.00)
-- ==============================================================================
CREATE TABLE transactions (
    transaction_id         VARCHAR2(64) PRIMARY KEY,
    from_account_id        VARCHAR2(64) NOT NULL,
    to_account_id          VARCHAR2(64),
    type                   VARCHAR2(30) NOT NULL,
    currency               VARCHAR2(3) DEFAULT 'PHP' NOT NULL,
    amount                 NUMBER(18, 4) NOT NULL,
    before_balance         NUMBER(18, 4) NOT NULL,
    after_balance          NUMBER(18, 4) NOT NULL,
    status                 VARCHAR2(30) NOT NULL,
    requires_2fa_otp       NUMBER(1) DEFAULT 0 NOT NULL,
    approved_by_user_id    VARCHAR2(64),
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
    CONSTRAINT fk_tx_approved_by FOREIGN KEY (approved_by_user_id) REFERENCES users(user_id),
    CONSTRAINT fk_tx_reversed_by FOREIGN KEY (reversed_by_user_id) REFERENCES users(user_id),
    CONSTRAINT chk_tx_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),
    CONSTRAINT chk_tx_status CHECK (status IN ('PENDING_APPROVAL', 'COMMITTED', 'FAILED', 'REJECTED_FRAUD', 'REVERSED', 'CANCELLED', 'POSTED', 'INITIATED', 'PROCESSING')),
    CONSTRAINT chk_tx_2fa_otp CHECK (requires_2fa_otp IN (0, 1)),
    CONSTRAINT chk_tx_amount CHECK (amount > 0)
);

-- ==============================================================================
-- 5. Table: outbox_events
-- ==============================================================================
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

-- ==============================================================================
-- 6. Table: notifications
-- ==============================================================================
CREATE TABLE notifications (
    notification_id VARCHAR2(64) PRIMARY KEY,
    user_id         VARCHAR2(64) NOT NULL,
    type            VARCHAR2(50) NOT NULL,
    message         CLOB NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT chk_notif_type CHECK (type IN ('TRANSACTION_ALERT', 'SECURITY_ALERT', 'CUSTOMER_VERIFICATION_ALERT', 'AMLA_CTR_ALERT'))
);

-- Note: Authentication session tokens and revocations are persisted in Redis (redis-cache).

-- ==============================================================================
-- Performance Indexes
-- ==============================================================================
CREATE INDEX idx_acc_user ON accounts(user_id);
CREATE INDEX idx_tx_from_acc ON transactions(from_account_id, created_at DESC);
CREATE INDEX idx_tx_to_acc ON transactions(to_account_id, created_at DESC);
CREATE INDEX idx_tx_status ON transactions(status);
CREATE INDEX idx_outbox_status ON outbox_events(status, created_at);
CREATE INDEX idx_notif_user ON notifications(user_id, sent_at DESC);

-- ==============================================================================
-- Seed Population: Realistic Banking Dataset
-- ==============================================================================

-- 1. Users
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES (
    'usr-1001-cst-001', 'Juan', 'Santos', 'Dela Cruz', 'juan.dc@email.com', '+639171234567',
    TO_DATE('1990-05-15', 'YYYY-MM-DD'), 'PASSPORT-P9876543A', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES (
    'usr-1002-cst-002', 'Maria', 'Clara', 'Reyes', 'maria.reyes@eastwestbanker.com', '+639189876543',
    TO_DATE('1992-08-20', 'YYYY-MM-DD'), 'UMID-0111-2233445-6', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES (
    'usr-1003-tel-001', 'Crisostomo', 'Alfonso', 'Ibarra', 'crisostomo.ibarra@eastwestbanker.com', '+639201112233',
    TO_DATE('1985-01-10', 'YYYY-MM-DD'), 'DRIVERS-LIC-N01-90-123456', 'TELLER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES (
    'usr-1005-boo-001', 'Beatriz', 'Santos', 'Ocampo', 'beatriz.ocampo@bank.com', '+639204445566',
    TO_DATE('1984-07-19', 'YYYY-MM-DD'), 'PRC-9988-7711', 'ADMIN',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES (
    'usr-1006-mgr-002', 'Carlos', 'Eduardo', 'Mendoza', 'carlos.mendoza@bank.com', '+639171122334',
    TO_DATE('1982-11-05', 'YYYY-MM-DD'), 'PRC-5544-3322', 'ADMIN',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES (
    'usr-1004-adm-001', 'Diana', 'Core', 'Administrator', 'diana.admin@bank.com', '+639000000000',
    TO_DATE('1980-01-01', 'YYYY-MM-DD'), 'COMPANY-ID-EMP-001', 'ADMIN',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', NULL, 10, 0, 'ACTIVE'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status,
    last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip
) VALUES (
    'usr-1007-sec-003', 'Alex', 'James', 'Rivera', 'alex.rivera@bank.com', '+639178889900',
    TO_DATE('1988-04-18', 'YYYY-MM-DD'), 'PRC-7788-9900', 'ADMIN',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE',
    14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status,
    last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip
) VALUES (
    'usr-2003-cst-003', 'Jose', 'Protacio', 'Rizal', 'jose.rizal@retailbank.ph', '+639195556677',
    TO_DATE('1987-06-19', 'YYYY-MM-DD'), 'PRC-1861-1234', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE',
    14.2117, 121.1656, 'Calamba, Laguna, Philippines', '112.198.33.15'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status,
    last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip
) VALUES (
    'usr-2004-cst-004', 'Andres', 'Castro', 'Bonifacio', 'andres.bonifacio@retailbank.ph', '+639173334455',
    TO_DATE('1989-11-30', 'YYYY-MM-DD'), 'PSA-1863-1130', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE',
    7.1907, 125.4578, 'Davao City, Philippines', '112.198.99.77'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status,
    last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip
) VALUES (
    'usr-2005-cst-005', 'Gabriela', 'Cario', 'Silang', 'gabriela.silang@retailbank.ph', '+639178881122',
    TO_DATE('1991-03-19', 'YYYY-MM-DD'), 'PSA-1988-1234', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE',
    17.5705, 120.3878, 'Vigan, Ilocos Sur, Philippines', '112.198.71.12'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status,
    last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip
) VALUES (
    'usr-2006-cst-006', 'Emilio', 'Dizon', 'Jacinto', 'emilio.jacinto@retailbank.ph', '+639192223344',
    TO_DATE('1993-12-15', 'YYYY-MM-DD'), 'PSA-1991-5678', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE',
    14.6760, 121.0437, 'Quezon City, Philippines', '112.198.22.44'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status,
    last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip
) VALUES (
    'usr-2007-cst-007', 'Melchora', 'Aquino', 'Ramos', 'melchora.aquino@retailbank.ph', '+639174445566',
    TO_DATE('1984-01-06', 'YYYY-MM-DD'), 'PSA-1980-9988', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE',
    14.6507, 120.9830, 'Caloocan, Philippines', '112.198.63.89'
);

INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob,
    government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status,
    last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip
) VALUES (
    'usr-2008-cst-008', 'Apolinario', 'Marasigan', 'Mabini', 'apolinario.mabini@retailbank.ph', '+639187778899',
    TO_DATE('1986-07-23', 'YYYY-MM-DD'), 'PSA-1984-7766', 'CUSTOMER',
    '$2a$10$4gw7WpRKwOnwNP5i8AQR2e9raJhzryXNsf7Qu.LxSt7alkeeN9nAS', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE',
    13.7565, 121.0583, 'Batangas City, Philippines', '112.198.54.33'
);

-- 2. Accounts (Savings Only: Exactly 1 per Customer)
INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3001', 'usr-1001-cst-001', '1000-2000-3001', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3002', 'usr-1002-cst-002', '1000-2000-3002', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3004', 'usr-2003-cst-003', '1000-2000-3004', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3005', 'usr-2004-cst-004', '1000-2000-3005', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3006', 'usr-2005-cst-005', '1000-2000-3006', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3007', 'usr-2006-cst-006', '1000-2000-3007', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3008', 'usr-2007-cst-007', '1000-2000-3008', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO accounts (account_id, user_id, account_number, account_type, status, credit_limit)
VALUES ('1000-2000-3009', 'usr-2008-cst-008', '1000-2000-3009', 'SAVINGS', 'ACTIVE', 0.0000);

-- 3. Balance Master (Exact 4-decimal precision with Surrogate balance_id PK)
INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3001', '1000-2000-3001', 25000000.0000, 0.0000);

INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3002', '1000-2000-3002', 5000000.0000, 0.0000);

INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3004', '1000-2000-3004', 5200000.0000, 0.0000);

INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3005', '1000-2000-3005', 3750000.0000, 0.0000);

INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3006', '1000-2000-3006', 4200000.0000, 0.0000);

INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3007', '1000-2000-3007', 6800000.0000, 0.0000);

INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3008', '1000-2000-3008', 2950000.0000, 0.0000);

INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount)
VALUES ('bal-1000-2000-3009', '1000-2000-3009', 9100000.0000, 0.0000);

COMMIT;

-- ==============================================================================
-- 7. Multi-Schema Synonyms for JPA Entities (core, integration, auth_identity)
-- ==============================================================================
DECLARE
    PROCEDURE create_user_if_missing(p_username IN VARCHAR2, p_password IN VARCHAR2) IS
        v_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO v_count FROM all_users WHERE username = UPPER(p_username);
        IF v_count = 0 THEN
            EXECUTE IMMEDIATE 'CREATE USER ' || p_username || ' IDENTIFIED BY "' || p_password || '"';
            EXECUTE IMMEDIATE 'GRANT CREATE SESSION, CREATE SYNONYM TO ' || p_username;
        END IF;
    END;
BEGIN
    create_user_if_missing('CORE', 'CorePass123#');
    create_user_if_missing('INTEGRATION', 'IntegrationPass123#');
    create_user_if_missing('AUTH_IDENTITY', 'AuthIdentityPass123#');
END;
/

DECLARE
    TYPE t_str_list IS TABLE OF VARCHAR2(64);
    v_core_tables t_str_list := t_str_list(
        'ACCOUNTS', 'AMOUNT_HOLDS', 'BALANCE_MASTER', 'COB_BATCH_LOG',
        'EOD_BALANCE_SNAPSHOTS', 'GL_ACCOUNTS', 'GL_BALANCES', 'GL_LEDGER',
        'INTEREST_ACCRUALS', 'REVERSAL_REQUESTS', 'SYSTEM_DATES',
        'TRANSACTIONS', 'TRANSACTION_STATUS_HISTORY', 'UNCOLLECTED_FEES'
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
