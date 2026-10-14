-- ==============================================================================
-- Team demo accounts for Azure SQL (mirror of infrastructure/oracle/03_seed_sample_data.sql)
-- Login: <name>@bank.com / password123   (wax, hans, jm, zel, jessy, maye, angel)
-- Accounts 1000-8801-0001 .. 0007, PHP 1,000,000.00 each.
-- DEMO DATA ONLY: shared password, remove before any real use.
-- last_login_at is set so these accounts skip the first-login OTP, same as local dev.
-- ==============================================================================

INSERT INTO auth_identity.users (
    user_id, first_name, middle_name, last_name, email, phone_number, dob, government_id,
    role, password_hash, pin_hash, max_concurrent_sessions, failed_login_attempts, status, last_login_at
) VALUES
('USR-TM-WAX',   'Wax',   'Aura', 'Team', 'wax@bank.com',   '09990001001', '1995-01-01', 'PSA-TM-WAX01',   'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 5, 0, 'ACTIVE', '2024-01-01T00:00:00+00:00'),
('USR-TM-HANS',  'Hans',  'Aura', 'Team', 'hans@bank.com',  '09990001002', '1995-01-02', 'PSA-TM-HANS02',  'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 5, 0, 'ACTIVE', '2024-01-01T00:00:00+00:00'),
('USR-TM-JM',    'JM',    'Aura', 'Team', 'jm@bank.com',    '09990001003', '1995-01-03', 'PSA-TM-JM03',    'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 5, 0, 'ACTIVE', '2024-01-01T00:00:00+00:00'),
('USR-TM-ZEL',   'Zel',   'Aura', 'Team', 'zel@bank.com',   '09990001004', '1995-01-04', 'PSA-TM-ZEL04',   'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 5, 0, 'ACTIVE', '2024-01-01T00:00:00+00:00'),
('USR-TM-JESSY', 'Jessy', 'Aura', 'Team', 'jessy@bank.com', '09990001005', '1995-01-05', 'PSA-TM-JESSY05', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 5, 0, 'ACTIVE', '2024-01-01T00:00:00+00:00'),
('USR-TM-MAYE',  'Maye',  'Aura', 'Team', 'maye@bank.com',  '09990001006', '1995-01-06', 'PSA-TM-MAYE06',  'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 5, 0, 'ACTIVE', '2024-01-01T00:00:00+00:00'),
('USR-TM-ANGEL', 'Angel', 'Aura', 'Team', 'angel@bank.com', '09990001007', '1995-01-07', 'PSA-TM-ANGEL07', 'CUSTOMER', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL, 5, 0, 'ACTIVE', '2024-01-01T00:00:00+00:00');

INSERT INTO auth_identity.admin_users (
    admin_id, first_name, middle_name, last_name, email, phone_number, dob, government_id,
    role, department, password_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES
('USR-TM-WAX',   'Wax',   'Aura', 'Team', 'wax@bank.com',   '09990001001', '1995-01-01', 'PSA-TM-WAX01',   'ADMIN',  'Executive Board',   '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE'),
('USR-TM-HANS',  'Hans',  'Aura', 'Team', 'hans@bank.com',  '09990001002', '1995-01-02', 'PSA-TM-HANS02',  'ADMIN',  'Executive Board',   '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE'),
('USR-TM-JM',    'JM',    'Aura', 'Team', 'jm@bank.com',    '09990001003', '1995-01-03', 'PSA-TM-JM03',    'TELLER', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE'),
('USR-TM-ZEL',   'Zel',   'Aura', 'Team', 'zel@bank.com',   '09990001004', '1995-01-04', 'PSA-TM-ZEL04',   'ADMIN',  'Executive Board',   '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE'),
('USR-TM-JESSY', 'Jessy', 'Aura', 'Team', 'jessy@bank.com', '09990001005', '1995-01-05', 'PSA-TM-JESSY05', 'TELLER', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE'),
('USR-TM-MAYE',  'Maye',  'Aura', 'Team', 'maye@bank.com',  '09990001006', '1995-01-06', 'PSA-TM-MAYE06',  'ADMIN',  'Executive Board',   '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE'),
('USR-TM-ANGEL', 'Angel', 'Aura', 'Team', 'angel@bank.com', '09990001007', '1995-01-07', 'PSA-TM-ANGEL07', 'TELLER', 'Branch Operations', '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', 5, 0, 'ACTIVE');

INSERT INTO core.accounts (account_id, user_id, account_number, account_type, status, credit_limit) VALUES
('1000-8801-0001', 'USR-TM-WAX',   '1000-8801-0001', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-8801-0002', 'USR-TM-HANS',  '1000-8801-0002', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-8801-0003', 'USR-TM-JM',    '1000-8801-0003', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-8801-0004', 'USR-TM-ZEL',   '1000-8801-0004', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-8801-0005', 'USR-TM-JESSY', '1000-8801-0005', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-8801-0006', 'USR-TM-MAYE',  '1000-8801-0006', 'SAVINGS', 'ACTIVE', 0.0000),
('1000-8801-0007', 'USR-TM-ANGEL', '1000-8801-0007', 'SAVINGS', 'ACTIVE', 0.0000);

INSERT INTO core.balance_master (balance_id, account_id, balance_amount, hold_amount) VALUES
('bal-1000-8801-0001', '1000-8801-0001', 1000000.0000, 0.0000),
('bal-1000-8801-0002', '1000-8801-0002', 1000000.0000, 0.0000),
('bal-1000-8801-0003', '1000-8801-0003', 1000000.0000, 0.0000),
('bal-1000-8801-0004', '1000-8801-0004', 1000000.0000, 0.0000),
('bal-1000-8801-0005', '1000-8801-0005', 1000000.0000, 0.0000),
('bal-1000-8801-0006', '1000-8801-0006', 1000000.0000, 0.0000),
('bal-1000-8801-0007', '1000-8801-0007', 1000000.0000, 0.0000);
