-- ==============================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- Epic B: Database & Schema Design (Oracle XE 21c Master Database)
-- File: 03_seed_sample_data.sql
-- Description: Inserts realistic seed test data matching the Capstone
--              specification (Customers, Tellers, Admins, Accounts, Balances,
--              Credit Assessments, Transactions, and Outbox Events).
-- ==============================================================================

SET ECHO ON;
SET FEEDBACK ON;

-- ------------------------------------------------------------------------------
-- 1. SEED USERS
-- ------------------------------------------------------------------------------
-- Customer 1: Juan Dela Cruz
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    created_at, updated_at
) VALUES (
    'U1001', 'Juan', 'Reyes', 'Dela Cruz', 'juan.dc@email.com', '09171234567',
    TO_DATE('1990-05-14', 'YYYY-MM-DD'), 'PSA-1234-5678', 'CUSTOMER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC',
    '$2a$12$k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8',
    3, 0, 'ACTIVE',
    TIMESTAMP '2024-01-10 09:15:00 UTC', TIMESTAMP '2024-01-10 09:15:00 UTC'
);

-- Customer 2: Maria Santos
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    created_at, updated_at
) VALUES (
    'U1002', 'Maria', 'Clara', 'Santos', 'maria.s@email.com', '09187654321',
    TO_DATE('1992-08-22', 'YYYY-MM-DD'), 'PASSPORT-9876-5432', 'CUSTOMER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC',
    '$2a$12$k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8',
    3, 0, 'ACTIVE',
    TIMESTAMP '2024-01-12 10:00:00 UTC', TIMESTAMP '2024-01-12 10:00:00 UTC'
);

-- Customer 3: Elijah Riley Montefalco
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    created_at, updated_at
) VALUES (
    'USR-159305', 'Elijah Riley', 'Santos', 'Montefalco', 'elijahriley.montefalco@gmail.com', '09678304637',
    TO_DATE('1994-04-12', 'YYYY-MM-DD'), 'PSA-1994-1593', 'CUSTOMER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC',
    '$2a$12$k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8',
    3, 0, 'ACTIVE',
    TIMESTAMP '2024-01-15 10:00:00 UTC', TIMESTAMP '2024-01-15 10:00:00 UTC'
);

-- Bank Staff 1: Teller Alex Mercer
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    created_at, updated_at
) VALUES (
    'U3001', 'Alex', 'James', 'Mercer', 'alex.teller@bank.com', '09201112233',
    TO_DATE('1988-11-03', 'YYYY-MM-DD'), 'PRC-8877-6655', 'TELLER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC',
    NULL,
    3, 0, 'ACTIVE',
    TIMESTAMP '2023-11-01 08:30:00 UTC', TIMESTAMP '2023-11-01 08:30:00 UTC'
);

-- Bank Staff 2: Branch Operations Officer (Teller) Beatriz Ocampo
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    created_at, updated_at
) VALUES (
    'U3002', 'Beatriz', 'Santos', 'Ocampo', 'beatriz.manager@bank.com', '09204445566',
    TO_DATE('1984-07-19', 'YYYY-MM-DD'), 'PRC-9988-7711', 'TELLER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC',
    NULL,
    3, 0, 'ACTIVE',
    TIMESTAMP '2023-10-01 08:30:00 UTC', TIMESTAMP '2023-10-01 08:30:00 UTC'
);

-- Bank Staff 3: Operations (Teller) Carlos Mendoza
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    created_at, updated_at
) VALUES (
    'U3003', 'Carlos', 'Eduardo', 'Mendoza', 'carlos.branchhead@bank.com', '09171122334',
    TO_DATE('1982-11-05', 'YYYY-MM-DD'), 'PRC-5544-3322', 'TELLER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC',
    NULL,
    3, 0, 'ACTIVE',
    TIMESTAMP '2023-09-15 08:30:00 UTC', TIMESTAMP '2023-09-15 08:30:00 UTC'
);

-- Bank Staff 3: Admin / IT Super User Diana Vance
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    created_at, updated_at
) VALUES (
    'U0001', 'Diana', 'Marie', 'Vance', 'diana.admin@bank.com', '09190001122',
    TO_DATE('1985-03-12', 'YYYY-MM-DD'), 'GOV-1122-3344', 'ADMIN',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC',
    NULL,
    5, 0, 'ACTIVE',
    TIMESTAMP '2023-01-15 08:00:00 UTC', TIMESTAMP '2023-01-15 08:00:00 UTC'
);

-- Team Member 1: Wax (Admin in Console)
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status, last_login_at,
    created_at, updated_at
) VALUES (
    'USR-TM-WAX', 'Wax', 'Aura', 'Team', 'wax@bank.com', '09990001001',
    TO_DATE('1995-01-01', 'YYYY-MM-DD'), 'PSA-TM-WAX01', 'ADMIN',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL,
    5, 0, 'ACTIVE', TIMESTAMP '2024-01-01 00:00:00 UTC',
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);

-- Team Member 2: Hans (Admin in Console)
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status, last_login_at,
    created_at, updated_at
) VALUES (
    'USR-TM-HANS', 'Hans', 'Aura', 'Team', 'hans@bank.com', '09990001002',
    TO_DATE('1995-01-02', 'YYYY-MM-DD'), 'PSA-TM-HANS02', 'ADMIN',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL,
    5, 0, 'ACTIVE', TIMESTAMP '2024-01-01 00:00:00 UTC',
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);

-- Team Member 3: JM (Teller in Console)
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status, last_login_at,
    created_at, updated_at
) VALUES (
    'USR-TM-JM', 'JM', 'Aura', 'Team', 'jm@bank.com', '09990001003',
    TO_DATE('1995-01-03', 'YYYY-MM-DD'), 'PSA-TM-JM03', 'TELLER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL,
    5, 0, 'ACTIVE', TIMESTAMP '2024-01-01 00:00:00 UTC',
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);

-- Team Member 4: Zel (Admin in Console)
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status, last_login_at,
    created_at, updated_at
) VALUES (
    'USR-TM-ZEL', 'Zel', 'Aura', 'Team', 'zel@bank.com', '09990001004',
    TO_DATE('1995-01-04', 'YYYY-MM-DD'), 'PSA-TM-ZEL04', 'ADMIN',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL,
    5, 0, 'ACTIVE', TIMESTAMP '2024-01-01 00:00:00 UTC',
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);

-- Team Member 5: Jessy (Teller in Console)
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status, last_login_at,
    created_at, updated_at
) VALUES (
    'USR-TM-JESSY', 'Jessy', 'Aura', 'Team', 'jessy@bank.com', '09990001005',
    TO_DATE('1995-01-05', 'YYYY-MM-DD'), 'PSA-TM-JESSY05', 'TELLER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL,
    5, 0, 'ACTIVE', TIMESTAMP '2024-01-01 00:00:00 UTC',
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);

-- Team Member 6: Maye (Admin in Console)
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status, last_login_at,
    created_at, updated_at
) VALUES (
    'USR-TM-MAYE', 'Maye', 'Aura', 'Team', 'maye@bank.com', '09990001006',
    TO_DATE('1995-01-06', 'YYYY-MM-DD'), 'PSA-TM-MAYE06', 'ADMIN',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL,
    5, 0, 'ACTIVE', TIMESTAMP '2024-01-01 00:00:00 UTC',
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);

-- Team Member 7: Angel (Teller in Console)
INSERT INTO users (
    user_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash, pin_hash,
    max_concurrent_sessions, failed_login_attempts, status, last_login_at,
    created_at, updated_at
) VALUES (
    'USR-TM-ANGEL', 'Angel', 'Aura', 'Team', 'angel@bank.com', '09990001007',
    TO_DATE('1995-01-07', 'YYYY-MM-DD'), 'PSA-TM-ANGEL07', 'TELLER',
    '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC', NULL,
    5, 0, 'ACTIVE', TIMESTAMP '2024-01-01 00:00:00 UTC',
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);


-- ------------------------------------------------------------------------------
-- 2. SEED ACCOUNTS
-- ------------------------------------------------------------------------------
-- Juan's Savings Account (A2001)
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit,
    created_at, updated_at
) VALUES (
    'A2001', 'U1001', '1000-2000-3001', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-10 09:20:00 UTC', TIMESTAMP '2024-01-10 09:20:00 UTC'
);

-- Maria's Savings Account (A2002)
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit,
    created_at, updated_at
) VALUES (
    'A2002', 'U1002', '1000-2000-3002', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-12 10:15:00 UTC', TIMESTAMP '2024-01-12 10:15:00 UTC'
);

-- Elijah's Savings Account (1000-4491-0023)
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit,
    created_at, updated_at
) VALUES (
    '1000-4491-0023', 'USR-159305', '1000-4491-0023', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-15 10:15:00 UTC', TIMESTAMP '2024-01-15 10:15:00 UTC'
);

-- Team Member Accounts
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit, created_at, updated_at
) VALUES (
    '1000-8801-0001', 'USR-TM-WAX', '1000-8801-0001', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit, created_at, updated_at
) VALUES (
    '1000-8801-0002', 'USR-TM-HANS', '1000-8801-0002', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit, created_at, updated_at
) VALUES (
    '1000-8801-0003', 'USR-TM-JM', '1000-8801-0003', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit, created_at, updated_at
) VALUES (
    '1000-8801-0004', 'USR-TM-ZEL', '1000-8801-0004', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit, created_at, updated_at
) VALUES (
    '1000-8801-0005', 'USR-TM-JESSY', '1000-8801-0005', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit, created_at, updated_at
) VALUES (
    '1000-8801-0006', 'USR-TM-MAYE', '1000-8801-0006', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO accounts (
    account_id, user_id, account_number, account_type, status, credit_limit, created_at, updated_at
) VALUES (
    '1000-8801-0007', 'USR-TM-ANGEL', '1000-8801-0007', 'SAVINGS', 'ACTIVE', 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);

-- ------------------------------------------------------------------------------
-- 3. SEED BALANCE MASTER
-- Note: available_balance is automatically calculated via trg_calc_available_balance
-- ------------------------------------------------------------------------------
-- Balance for Juan's Savings Account A2001 (Balance: 298,000, Hold: 0)
INSERT INTO balance_master (
    account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'A2001', 298000.0000, 0.0000,
    TIMESTAMP '2024-01-10 09:20:00 UTC', TIMESTAMP '2024-06-01 14:32:00 UTC'
);

-- Balance for Maria's Savings Account A2002 (Balance: 52,000, Hold: 0)
INSERT INTO balance_master (
    account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'A2002', 52000.0000, 0.0000,
    TIMESTAMP '2024-01-12 10:15:00 UTC', TIMESTAMP '2024-06-01 14:32:00 UTC'
);

-- Balance for Elijah's Savings Account 1000-4491-0023 (Balance: 250,000, Hold: 0)
INSERT INTO balance_master (
    account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    '1000-4491-0023', 250000.0000, 0.0000,
    TIMESTAMP '2024-01-15 10:15:00 UTC', TIMESTAMP '2024-06-01 14:32:00 UTC'
);

-- Balance for Team Members (PHP 1,000,000.00 each)
INSERT INTO balance_master (
    balance_id, account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'bal-1000-8801-0001', '1000-8801-0001', 1000000.0000, 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO balance_master (
    balance_id, account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'bal-1000-8801-0002', '1000-8801-0002', 1000000.0000, 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO balance_master (
    balance_id, account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'bal-1000-8801-0003', '1000-8801-0003', 1000000.0000, 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO balance_master (
    balance_id, account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'bal-1000-8801-0004', '1000-8801-0004', 1000000.0000, 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO balance_master (
    balance_id, account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'bal-1000-8801-0005', '1000-8801-0005', 1000000.0000, 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO balance_master (
    balance_id, account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'bal-1000-8801-0006', '1000-8801-0006', 1000000.0000, 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);
INSERT INTO balance_master (
    balance_id, account_id, balance_amount, hold_amount, created_at, updated_at
) VALUES (
    'bal-1000-8801-0007', '1000-8801-0007', 1000000.0000, 0.0000,
    TIMESTAMP '2024-01-01 00:00:00 UTC', TIMESTAMP '2024-01-01 00:00:00 UTC'
);


-- ------------------------------------------------------------------------------
-- 5. SEED TRANSACTIONS
-- Transaction T5001: Juan transfers PHP 2,000 to Maria's Account
-- ------------------------------------------------------------------------------
INSERT INTO transactions (
    transaction_id, from_account_id, to_account_id, type, amount,
    before_balance, after_balance, status, requires_maker_checker,
    approved_by_user_id, created_at, updated_at
) VALUES (
    'T5001', 'A2001', 'A2002', 'TRANSFER', 2000.0000,
    300000.0000, 298000.0000, 'COMMITTED', 0,
    NULL,
    TIMESTAMP '2024-06-01 14:32:00 UTC', TIMESTAMP '2024-06-01 14:32:00 UTC'
);


-- ------------------------------------------------------------------------------
-- 6. SEED TRANSACTIONAL OUTBOX EVENTS
-- Outbox record for T5001 ready for Kafka / RabbitMQ streaming
-- ------------------------------------------------------------------------------
INSERT INTO outbox_events (
    event_id, aggregate_type, aggregate_id, event_type, kafka_topic,
    payload, status, retry_count, created_at, published_at
) VALUES (
    'EVT-9001', 'TRANSACTION', 'T5001', 'MUTATION_COMMITTED', 'transaction-events',
    '{"transactionId":"T5001","fromAccount":"A2001","toAccount":"A2002","amount":2000.0000,"status":"COMMITTED"}',
    'PUBLISHED', 0,
    TIMESTAMP '2024-06-01 14:32:00 UTC', TIMESTAMP '2024-06-01 14:32:00 UTC'
);


-- ------------------------------------------------------------------------------
-- 7. SEED NOTIFICATIONS
-- Transaction alert sent to Juan for transfer T5001
-- ------------------------------------------------------------------------------
INSERT INTO notifications (
    notification_id, user_id, type, message,
    sent_at, created_at, updated_at
) VALUES (
    'N7001', 'U1001', 'TRANSACTION_ALERT',
    'Transfer of PHP 2,000.00 sent from account 1000-2000-3001. New available balance: PHP 298,000.00.',
    TIMESTAMP '2024-06-01 14:32:01 UTC', TIMESTAMP '2024-06-01 14:32:01 UTC', TIMESTAMP '2024-06-01 14:32:01 UTC'
);

-- Commit all inserted seed data
COMMIT;
