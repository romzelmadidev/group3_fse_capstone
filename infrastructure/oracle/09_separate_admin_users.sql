-- ==============================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- Migration: 08_separate_admin_users.sql
-- Description: Segregates retail customers (users) and bank staff (admin_users)
-- ==============================================================================

SET ECHO ON;
SET FEEDBACK ON;

-- ------------------------------------------------------------------------------
-- 1. CREATE ADMIN_USERS TABLE
-- ------------------------------------------------------------------------------
CREATE TABLE admin_users (
    admin_id                 VARCHAR2(64) PRIMARY KEY,
    first_name              VARCHAR2(100) NOT NULL,
    middle_name             VARCHAR2(100),
    last_name               VARCHAR2(100) NOT NULL,
    email                   VARCHAR2(255) NOT NULL UNIQUE,
    phone_number            VARCHAR2(30),
    dob                     DATE,
    government_id           VARCHAR2(100),
    role                    VARCHAR2(20) NOT NULL CHECK (role IN ('ADMIN', 'TELLER', 'MANAGER', 'COMPLIANCE')),
    department              VARCHAR2(100) DEFAULT 'Branch Operations',
    password_hash           VARCHAR2(255) NOT NULL,
    max_concurrent_sessions NUMBER(3) DEFAULT 5 NOT NULL,
    failed_login_attempts   NUMBER(3) DEFAULT 0 NOT NULL,
    status                  VARCHAR2(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'SUSPENDED')),
    last_login_at           TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

COMMENT ON TABLE admin_users IS 'Dedicated table for bank administrative, branch operations, and compliance personnel';
COMMENT ON COLUMN admin_users.admin_id IS 'Unique identifier for staff / admin user';
COMMENT ON COLUMN admin_users.role IS 'Authorized operational role: ADMIN, TELLER, MANAGER, or COMPLIANCE';

-- ------------------------------------------------------------------------------
-- 2. MIGRATE STAFF AND ADMIN USERS INTO ADMIN_USERS
-- ------------------------------------------------------------------------------
INSERT INTO admin_users (
    admin_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    last_login_at, created_at, updated_at
)
SELECT 
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    last_login_at, created_at, updated_at
FROM users 
WHERE role IN ('ADMIN', 'TELLER', 'MANAGER');

-- Ensure seeds from 03_seed_sample_data (U0001, U3001, U3002, U3003) exist in admin_users if not already
MERGE INTO admin_users dst
USING (
    SELECT 'U0001' AS admin_id, 'Diana' AS first_name, NULL AS middle_name, 'Vance' AS last_name, 
           'diana.admin@bank.com' AS email, '09191112233' AS phone_number, TO_DATE('1985-04-18', 'YYYY-MM-DD') AS dob,
           'PRC-0011-2233' AS government_id, 'ADMIN' AS role, 'Operations' AS department,
           '.GqUn1B3BkspBIiuTkmN.1Jwf1PC' AS password_hash,
           5 AS max_concurrent_sessions, 0 AS failed_login_attempts, 'ACTIVE' AS status,
           CURRENT_TIMESTAMP AS created_at, CURRENT_TIMESTAMP AS updated_at FROM DUAL
) src
ON (dst.admin_id = src.admin_id OR dst.email = src.email)
WHEN NOT MATCHED THEN
INSERT (admin_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, department, password_hash, max_concurrent_sessions, failed_login_attempts, status, created_at, updated_at)
VALUES (src.admin_id, src.first_name, src.middle_name, src.last_name, src.email, src.phone_number, src.dob, src.government_id, src.role, src.department, src.password_hash, src.max_concurrent_sessions, src.failed_login_attempts, src.status, src.created_at, src.updated_at);

MERGE INTO admin_users dst
USING (
    SELECT 'U3001' AS admin_id, 'Alex' AS first_name, 'James' AS middle_name, 'Mercer' AS last_name, 
           'alex.teller@bank.com' AS email, '09201112233' AS phone_number, TO_DATE('1988-11-03', 'YYYY-MM-DD') AS dob,
           'PRC-8877-6655' AS government_id, 'TELLER' AS role, 'Branch Operations' AS department,
           '.GqUn1B3BkspBIiuTkmN.1Jwf1PC' AS password_hash,
           5 AS max_concurrent_sessions, 0 AS failed_login_attempts, 'ACTIVE' AS status,
           CURRENT_TIMESTAMP AS created_at, CURRENT_TIMESTAMP AS updated_at FROM DUAL
) src
ON (dst.admin_id = src.admin_id OR dst.email = src.email)
WHEN NOT MATCHED THEN
INSERT (admin_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, department, password_hash, max_concurrent_sessions, failed_login_attempts, status, created_at, updated_at)
VALUES (src.admin_id, src.first_name, src.middle_name, src.last_name, src.email, src.phone_number, src.dob, src.government_id, src.role, src.department, src.password_hash, src.max_concurrent_sessions, src.failed_login_attempts, src.status, src.created_at, src.updated_at);

-- ------------------------------------------------------------------------------
-- 3. ALIGN CUSTOMERS TABLE (USERS)
-- ------------------------------------------------------------------------------
-- Team members (USR-TM-*) retain their customer profile in users with role='CUSTOMER'
-- to preserve foreign key links to their demo savings accounts (1000-8801-0001 to 0007).
UPDATE users 
SET role = 'CUSTOMER' 
WHERE user_id LIKE 'USR-TM-%';

-- Delete pure staff accounts from users that are not customer account owners
DELETE FROM users 
WHERE user_id IN (
    'usr-1004-adm-001', 
    'usr-1005-boo-001', 
    'usr-1006-mgr-002', 
    'usr-1007-sec-003', 
    'usr-1003-tel-001',
    'U0001',
    'U3001',
    'U3002',
    'U3003'
);

-- ------------------------------------------------------------------------------
-- 4. UPDATE FOREIGN KEY CONSTRAINTS ON TRANSACTIONS
-- ------------------------------------------------------------------------------
-- Drop FK_TX_APPROVED_BY and FK_TX_REVERSED_BY so approval/reversal by an admin_id
-- does not fail against the users table.
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP CONSTRAINT fk_tx_approver';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -2443 THEN NULL; END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP CONSTRAINT fk_tx_approved_by';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -2443 THEN NULL; END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP CONSTRAINT fk_tx_reversed_by';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -2443 THEN NULL; END IF;
END;
/

-- ------------------------------------------------------------------------------
-- 5. CREATE UNIFIED VIEW V_ALL_USERS
-- ------------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_all_users AS
SELECT 
    user_id,
    first_name,
    middle_name,
    last_name,
    email,
    phone_number,
    dob,
    government_id,
    role,
    status,
    'CUSTOMER' AS user_type,
    last_login_at,
    created_at,
    updated_at
FROM users
UNION ALL
SELECT 
    admin_id AS user_id,
    first_name,
    middle_name,
    last_name,
    email,
    phone_number,
    dob,
    government_id,
    role,
    status,
    'ADMIN_STAFF' AS user_type,
    last_login_at,
    created_at,
    updated_at
FROM admin_users;

COMMIT;
EXIT;
