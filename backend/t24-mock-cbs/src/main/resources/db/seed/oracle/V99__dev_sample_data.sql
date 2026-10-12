-- ==============================================================================
-- Flyway Seed Data: V99__dev_sample_data.sql
-- Engine: Oracle Database 21c / 23ai
-- Description: Idempotent development & testing dataset (Users, Accounts, Balances)
-- Note: Uses MERGE INTO for every entity to guarantee zero duplicate keys,
--       zero unique constraint violations, and no overwriting of active balances.
-- ==============================================================================

-- 1. Development Users
MERGE INTO users target
USING (
    SELECT 'usr-1001-cst-001' AS user_id, 'Juan' AS first_name, 'Santos' AS middle_name, 'Dela Cruz' AS last_name, 'juan.dc@email.com' AS email, '+639171234567' AS phone_number, TO_DATE('1990-05-15', 'YYYY-MM-DD') AS dob, 'PASSPORT-P9876543A' AS government_id, 'CUSTOMER' AS role, '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC' AS password_hash, '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee' AS pin_hash, 3 AS max_concurrent_sessions, 0 AS failed_login_attempts, 'ACTIVE' AS status, 'PENDING' AS kyc_status, 14.5995 AS last_known_latitude, 120.9842 AS last_known_longitude, 'Manila, Philippines' AS last_known_location_name, '112.198.45.10' AS last_known_ip FROM dual UNION ALL
    SELECT 'usr-1002-cst-002', 'Maria', 'Clara', 'Reyes', 'maria.reyes@eastwestbanker.com', '+639189876543', TO_DATE('1992-08-20', 'YYYY-MM-DD'), 'UMID-0111-2233445-6', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'usr-1003-tel-001', 'Crisostomo', 'Alfonso', 'Ibarra', 'crisostomo.ibarra@eastwestbanker.com', '+639201112233', TO_DATE('1985-01-10', 'YYYY-MM-DD'), 'DRIVERS-LIC-N01-90-123456', 'TELLER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'usr-1005-boo-001', 'Beatriz', 'Santos', 'Ocampo', 'beatriz.ocampo@bank.com', '+639204445566', TO_DATE('1984-07-19', 'YYYY-MM-DD'), 'PRC-9988-7711', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'usr-1006-mgr-002', 'Carlos', 'Eduardo', 'Mendoza', 'carlos.mendoza@bank.com', '+639171122334', TO_DATE('1982-11-05', 'YYYY-MM-DD'), 'PRC-5544-3322', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'usr-1004-adm-001', 'Diana', 'Core', 'Administrator', 'diana.admin@bank.com', '+639000000000', TO_DATE('1980-01-01', 'YYYY-MM-DD'), 'COMPANY-ID-EMP-001', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 10, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'usr-1007-sec-003', 'Alex', 'James', 'Rivera', 'alex.rivera@bank.com', '+639178889900', TO_DATE('1988-04-18', 'YYYY-MM-DD'), 'PRC-7788-9900', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-TM-WAX', 'Wax', 'Aura', 'Team', 'wax@bank.com', '+639990001001', TO_DATE('1995-01-01', 'YYYY-MM-DD'), 'PSA-TM-WAX01', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-TM-HANS', 'Hans', 'Aura', 'Team', 'hans@bank.com', '+639990001002', TO_DATE('1995-01-02', 'YYYY-MM-DD'), 'PSA-TM-HANS02', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-TM-JM', 'JM', 'Aura', 'Team', 'jm@bank.com', '+639990001003', TO_DATE('1995-01-03', 'YYYY-MM-DD'), 'PSA-TM-JM03', 'TELLER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-TM-ZEL', 'Zel', 'Aura', 'Team', 'zel@bank.com', '+639990001004', TO_DATE('1995-01-04', 'YYYY-MM-DD'), 'PSA-TM-ZEL04', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-TM-JESSY', 'Jessy', 'Aura', 'Team', 'jessy@bank.com', '+639990001005', TO_DATE('1995-01-05', 'YYYY-MM-DD'), 'PSA-TM-JESSY05', 'TELLER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-TM-MAYE', 'Maye', 'Aura', 'Team', 'maye@bank.com', '+639990001006', TO_DATE('1995-01-06', 'YYYY-MM-DD'), 'PSA-TM-MAYE06', 'ADMIN', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-TM-ANGEL', 'Angel', 'Aura', 'Team', 'angel@bank.com', '+639990001007', TO_DATE('1995-01-07', 'YYYY-MM-DD'), 'PSA-TM-ANGEL07', 'TELLER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 5, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'usr-2003-cst-003', 'Jose', 'Protacio', 'Rizal', 'jose.rizal@retailbank.ph', '+639195556677', TO_DATE('1987-06-19', 'YYYY-MM-DD'), 'PRC-1861-1234', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 14.2117, 121.1656, 'Calamba, Laguna, Philippines', '112.198.33.15' FROM dual UNION ALL
    SELECT 'usr-2004-cst-004', 'Andres', 'Castro', 'Bonifacio', 'andres.bonifacio@retailbank.ph', '+639173334455', TO_DATE('1989-11-30', 'YYYY-MM-DD'), 'PSA-1863-1130', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 7.1907, 125.4578, 'Davao City, Philippines', '112.198.99.77' FROM dual UNION ALL
    SELECT 'usr-2005-cst-005', 'Gabriela', 'Cario', 'Silang', 'gabriela.silang@retailbank.ph', '+639178881122', TO_DATE('1991-03-19', 'YYYY-MM-DD'), 'PSA-1988-1234', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 17.5705, 120.3878, 'Vigan, Ilocos Sur, Philippines', '112.198.71.12' FROM dual UNION ALL
    SELECT 'usr-2006-cst-006', 'Emilio', 'Dizon', 'Jacinto', 'emilio.jacinto@retailbank.ph', '+639192223344', TO_DATE('1993-12-15', 'YYYY-MM-DD'), 'PSA-1991-5678', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 14.6760, 121.0437, 'Quezon City, Philippines', '112.198.22.44' FROM dual UNION ALL
    SELECT 'usr-2007-cst-007', 'Melchora', 'Aquino', 'Ramos', 'melchora.aquino@retailbank.ph', '+639174445566', TO_DATE('1984-01-06', 'YYYY-MM-DD'), 'PSA-1980-9988', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 14.6507, 120.9830, 'Caloocan, Philippines', '112.198.63.89' FROM dual UNION ALL
    SELECT 'usr-2008-cst-008', 'Apolinario', 'Marasigan', 'Mabini', 'apolinario.mabini@retailbank.ph', '+639187778899', TO_DATE('1986-07-23', 'YYYY-MM-DD'), 'PSA-1984-7766', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 13.7565, 121.0583, 'Batangas City, Philippines', '112.198.54.33' FROM dual UNION ALL
    SELECT 'USR-159305', 'Elijah Riley', 'Santos', 'Montefalco', 'elijahriley.montefalco@gmail.com', '+639678304637', TO_DATE('1994-04-12', 'YYYY-MM-DD'), 'PSA-1994-1593', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual UNION ALL
    SELECT 'USR-142004', 'Elijah', 'Riley', 'Montefalco', 'elijah.montefalco@bank.ph', '+639678304999', TO_DATE('1994-04-12', 'YYYY-MM-DD'), 'PSA-1994-1420', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', '$2a$10$e8V9m5gQ4F9oY9o8O8V7eeY9o8O8V7ee', 3, 0, 'ACTIVE', 'PENDING', 14.5995, 120.9842, 'Manila, Philippines', '112.198.45.10' FROM dual
) src
ON (target.user_id = src.user_id)
WHEN NOT MATCHED THEN
    INSERT (user_id, first_name, middle_name, last_name, email, phone_number, dob, government_id, role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status, kyc_status, last_known_latitude, last_known_longitude, last_known_location_name, last_known_ip)
    VALUES (src.user_id, src.first_name, src.middle_name, src.last_name, src.email, src.phone_number, src.dob, src.government_id, src.role, src.password_hash, src.pin_hash, src.max_concurrent_sessions, src.failed_login_attempts, src.status, src.kyc_status, src.last_known_latitude, src.last_known_longitude, src.last_known_location_name, src.last_known_ip);

-- 2. Development Accounts
MERGE INTO accounts target
USING (
    SELECT '1000-2000-3001' AS account_id, 'usr-1001-cst-001' AS user_id, '1000-2000-3001' AS account_number, 'SAVINGS' AS account_type, 'ACTIVE' AS status FROM dual UNION ALL
    SELECT '1000-2000-3002', 'usr-1002-cst-002', '1000-2000-3002', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3004', 'usr-2003-cst-003', '1000-2000-3004', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3005', 'usr-2004-cst-004', '1000-2000-3005', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3006', 'usr-2005-cst-005', '1000-2000-3006', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3007', 'usr-2006-cst-006', '1000-2000-3007', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3008', 'usr-2007-cst-007', '1000-2000-3008', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3009', 'usr-2008-cst-008', '1000-2000-3009', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3010', 'USR-159305', '1000-2000-3010', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-4491-0023', 'USR-159305', '1000-4491-0023', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-2000-3011', 'USR-142004', '1000-2000-3011', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-8801-0001', 'USR-TM-WAX', '1000-8801-0001', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-8801-0002', 'USR-TM-HANS', '1000-8801-0002', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-8801-0003', 'USR-TM-JM', '1000-8801-0003', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-8801-0004', 'USR-TM-ZEL', '1000-8801-0004', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-8801-0005', 'USR-TM-JESSY', '1000-8801-0005', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-8801-0006', 'USR-TM-MAYE', '1000-8801-0006', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT '1000-8801-0007', 'USR-TM-ANGEL', '1000-8801-0007', 'SAVINGS', 'ACTIVE' FROM dual UNION ALL
    SELECT 'ACC-NOSTRO-BANK-B', 'usr-1001-cst-001', '900100000001', 'SAVINGS', 'ACTIVE' FROM dual
) src
ON (target.account_id = src.account_id)
WHEN NOT MATCHED THEN
    INSERT (account_id, user_id, account_number, account_type, status)
    VALUES (src.account_id, src.user_id, src.account_number, src.account_type, src.status);

-- 3. Development Balances (Only seeded if account has no existing balance record)
MERGE INTO balance_master target
USING (
    SELECT 'bal-ACC-NOSTRO-BANK-B' AS balance_id, 'ACC-NOSTRO-BANK-B' AS account_id, 5000000.0000 AS balance_amount, 0.0000 AS hold_amount FROM dual UNION ALL
    SELECT 'bal-1000-2000-3001', '1000-2000-3001', 25000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3002', '1000-2000-3002', 5000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3004', '1000-2000-3004', 5200000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3005', '1000-2000-3005', 3750000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3006', '1000-2000-3006', 4200000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3007', '1000-2000-3007', 6800000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3008', '1000-2000-3008', 2950000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3009', '1000-2000-3009', 9100000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3010', '1000-2000-3010', 50000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-4491-0023', '1000-4491-0023', 250000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-2000-3011', '1000-2000-3011', 50000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-8801-0001', '1000-8801-0001', 1000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-8801-0002', '1000-8801-0002', 1000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-8801-0003', '1000-8801-0003', 1000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-8801-0004', '1000-8801-0004', 1000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-8801-0005', '1000-8801-0005', 1000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-8801-0006', '1000-8801-0006', 1000000.0000, 0.0000 FROM dual UNION ALL
    SELECT 'bal-1000-8801-0007', '1000-8801-0007', 1000000.0000, 0.0000 FROM dual
) src
ON (target.account_id = src.account_id)
WHEN NOT MATCHED THEN
    INSERT (balance_id, account_id, balance_amount, hold_amount)
    VALUES (src.balance_id, src.account_id, src.balance_amount, src.hold_amount);
