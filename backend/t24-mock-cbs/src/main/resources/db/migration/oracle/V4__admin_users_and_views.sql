-- ==============================================================================
-- Flyway Migration: V4__admin_users_and_views.sql
-- Engine: Oracle Database 21c / 23ai
-- Description: Creates admin_users table for bank administrative, branch operations,
--              and compliance personnel, migrating staff and creating v_all_users view.
-- ==============================================================================

DECLARE
    v_cnt NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_cnt FROM user_tables WHERE table_name = 'ADMIN_USERS';
    IF v_cnt = 0 THEN
        EXECUTE IMMEDIATE '
        CREATE TABLE admin_users (
            admin_id                 VARCHAR2(64) PRIMARY KEY,
            first_name              VARCHAR2(100) NOT NULL,
            middle_name             VARCHAR2(100),
            last_name               VARCHAR2(100) NOT NULL,
            email                   VARCHAR2(255) NOT NULL UNIQUE,
            phone_number            VARCHAR2(30),
            dob                     DATE,
            government_id           VARCHAR2(100),
            role                    VARCHAR2(20) NOT NULL CHECK (role IN (''ADMIN'', ''TELLER'', ''MANAGER'', ''COMPLIANCE'')),
            department              VARCHAR2(100) DEFAULT ''Branch Operations'',
            password_hash           VARCHAR2(255) NOT NULL,
            max_concurrent_sessions NUMBER(3) DEFAULT 5 NOT NULL,
            failed_login_attempts   NUMBER(3) DEFAULT 0 NOT NULL,
            status                  VARCHAR2(20) DEFAULT ''ACTIVE'' CHECK (status IN (''ACTIVE'', ''LOCKED'', ''SUSPENDED'')),
            last_login_at           TIMESTAMP WITH TIME ZONE,
            created_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
            updated_at              TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
        )';
    END IF;
END;
/

-- Seed pure bank staff into admin_users if not present
MERGE INTO admin_users dst
USING (
    SELECT 'U0001' AS admin_id, 'Diana' AS first_name, 'Marie' AS middle_name, 'Vance' AS last_name, 
           'diana.admin@bank.com' AS email, '+639000000000' AS phone_number, TO_DATE('1980-01-01', 'YYYY-MM-DD') AS dob,
           'COMPANY-ID-EMP-001' AS government_id, 'ADMIN' AS role, 'Executive Board' AS department,
           '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC' AS password_hash,
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
           '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC' AS password_hash,
           5 AS max_concurrent_sessions, 0 AS failed_login_attempts, 'ACTIVE' AS status,
           CURRENT_TIMESTAMP AS created_at, CURRENT_TIMESTAMP AS updated_at FROM DUAL
) src
ON (dst.admin_id = src.admin_id OR dst.email = src.email)
WHEN NOT MATCHED THEN
INSERT (admin_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, department, password_hash, max_concurrent_sessions, failed_login_attempts, status, created_at, updated_at)
VALUES (src.admin_id, src.first_name, src.middle_name, src.last_name, src.email, src.phone_number, src.dob, src.government_id, src.role, src.department, src.password_hash, src.max_concurrent_sessions, src.failed_login_attempts, src.status, src.created_at, src.updated_at);

-- Seed team members directly into admin_users
MERGE INTO admin_users dst
USING (
    SELECT 'USR-TM-WAX' AS admin_id, 'Wax' AS first_name, 'Aura' AS middle_name, 'Team' AS last_name, 'wax@bank.com' AS email, '+639990001001' AS phone_number, TO_DATE('1995-01-01', 'YYYY-MM-DD') AS dob, 'PSA-TM-WAX01' AS government_id, 'ADMIN' AS role, 'Engineering' AS department, '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC' AS password_hash, 5 AS max_concurrent_sessions, 0 AS failed_login_attempts, 'ACTIVE' AS status, CURRENT_TIMESTAMP AS created_at, CURRENT_TIMESTAMP AS updated_at FROM DUAL UNION ALL
    SELECT 'USR-TM-HANS', 'Hans', 'Aura', 'Team', 'hans@bank.com', '+639990001002', TO_DATE('1995-01-02', 'YYYY-MM-DD'), 'PSA-TM-HANS02', 'ADMIN', 'Engineering', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'USR-TM-JM', 'JM', 'Aura', 'Team', 'jm@bank.com', '+639990001003', TO_DATE('1995-01-03', 'YYYY-MM-DD'), 'PSA-TM-JM03', 'TELLER', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'USR-TM-ZEL', 'Zel', 'Aura', 'Team', 'zel@bank.com', '+639990001004', TO_DATE('1995-01-04', 'YYYY-MM-DD'), 'PSA-TM-ZEL04', 'ADMIN', 'Engineering', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'USR-TM-JESSY', 'Jessy', 'Aura', 'Team', 'jessy@bank.com', '+639990001005', TO_DATE('1995-01-05', 'YYYY-MM-DD'), 'PSA-TM-JESSY05', 'TELLER', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'USR-TM-MAYE', 'Maye', 'Aura', 'Team', 'maye@bank.com', '+639990001006', TO_DATE('1995-01-06', 'YYYY-MM-DD'), 'PSA-TM-MAYE06', 'ADMIN', 'Engineering', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'USR-TM-ANGEL', 'Angel', 'Aura', 'Team', 'angel@bank.com', '+639990001007', TO_DATE('1995-01-07', 'YYYY-MM-DD'), 'PSA-TM-ANGEL07', 'TELLER', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'usr-1005-boo-001', 'Beatriz', 'Santos', 'Ocampo', 'beatriz.ocampo@bank.com', '+639204445566', TO_DATE('1984-07-19', 'YYYY-MM-DD'), 'PRC-9988-7711', 'ADMIN', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'usr-1006-mgr-002', 'Carlos', 'Eduardo', 'Mendoza', 'carlos.mendoza@bank.com', '+639171122334', TO_DATE('1982-11-05', 'YYYY-MM-DD'), 'PRC-5544-3322', 'ADMIN', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL UNION ALL
    SELECT 'usr-1007-sec-003', 'Alex', 'James', 'Rivera', 'alex.rivera@bank.com', '+639178889900', TO_DATE('1988-04-18', 'YYYY-MM-DD'), 'PRC-7788-9900', 'ADMIN', 'Information Security', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL
) src
ON (dst.admin_id = src.admin_id OR dst.email = src.email)
WHEN NOT MATCHED THEN
INSERT (admin_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, department, password_hash, max_concurrent_sessions, failed_login_attempts, status, created_at, updated_at)
VALUES (src.admin_id, src.first_name, src.middle_name, src.last_name, src.email, src.phone_number, src.dob, src.government_id, src.role, src.department, src.password_hash, src.max_concurrent_sessions, src.failed_login_attempts, src.status, src.created_at, src.updated_at);

-- Migrate any staff remaining in users table
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
WHERE role IN ('ADMIN', 'TELLER', 'MANAGER')
  AND user_id NOT IN (SELECT admin_id FROM admin_users)
  AND email NOT IN (SELECT email FROM admin_users);

-- Align team members in users table to CUSTOMER to preserve savings accounts
UPDATE users 
SET role = 'CUSTOMER' 
WHERE user_id LIKE 'USR-TM-%' AND role != 'CUSTOMER';

-- Unified View for backwards-compatibility
CREATE OR REPLACE VIEW v_all_users AS
SELECT user_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, status, 'CUSTOMER' AS user_type, last_login_at, created_at, updated_at FROM users
UNION ALL
SELECT admin_id AS user_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, status, 'ADMIN_STAFF' AS user_type, last_login_at, created_at, updated_at FROM admin_users;
