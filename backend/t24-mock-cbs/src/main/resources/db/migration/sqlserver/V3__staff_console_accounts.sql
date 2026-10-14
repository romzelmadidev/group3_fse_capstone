-- ==============================================================================
-- Staff console accounts for Azure SQL (port of Oracle V101 + V4 staff seeds)
-- Staff and customers are separate identities with separate credentials:
--   customer app : wax@bank.com        / password123      (auth_identity.users)
--   staff console: wax.staff@bank.com  / Staff@Aura2026   (auth_identity.admin_users)
-- V2 seeded the team in admin_users under the customer emails; the console and
-- the account-service reject those, so re-key them here.
-- DEMO DATA ONLY: shared password, remove before any real use.
-- ==============================================================================

DELETE FROM auth_identity.admin_users WHERE admin_id LIKE 'USR-TM-%';

INSERT INTO auth_identity.admin_users (
    admin_id, first_name, middle_name, last_name, email, phone_number, dob, government_id,
    role, department, password_hash, max_concurrent_sessions, failed_login_attempts, status
) VALUES
('ADM-TM-WAX',   'Wax',   'Aura', 'Team', 'wax.staff@bank.com',   '09990001001', '1995-01-01', 'PSA-TM-WAX01',   'ADMIN',  'Executive Board',   '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE'),
('ADM-TM-HANS',  'Hans',  'Aura', 'Team', 'hans.staff@bank.com',  '09990001002', '1995-01-02', 'PSA-TM-HANS02',  'ADMIN',  'Executive Board',   '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE'),
('ADM-TM-JM',    'JM',    'Aura', 'Team', 'jm.staff@bank.com',    '09990001003', '1995-01-03', 'PSA-TM-JM03',    'TELLER', 'Branch Operations', '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE'),
('ADM-TM-ZEL',   'Zel',   'Aura', 'Team', 'zel.staff@bank.com',   '09990001004', '1995-01-04', 'PSA-TM-ZEL04',   'ADMIN',  'Executive Board',   '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE'),
('ADM-TM-JESSY', 'Jessy', 'Aura', 'Team', 'jessy.staff@bank.com', '09990001005', '1995-01-05', 'PSA-TM-JESSY05', 'TELLER', 'Branch Operations', '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE'),
('ADM-TM-MAYE',  'Maye',  'Aura', 'Team', 'maye.staff@bank.com',  '09990001006', '1995-01-06', 'PSA-TM-MAYE06',  'ADMIN',  'Executive Board',   '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE'),
('ADM-TM-ANGEL', 'Angel', 'Aura', 'Team', 'angel.staff@bank.com', '09990001007', '1995-01-07', 'PSA-TM-ANGEL07', 'TELLER', 'Branch Operations', '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE'),
('usr-1003-tel-001', 'Crisostomo', 'Alfonso', 'Ibarra', 'crisostomo.ibarra@eastwestbanker.com', '+639201112233', '1985-01-10', 'DRIVERS-LIC-N01-90-123456', 'TELLER', 'Branch Operations', '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi', 5, 0, 'ACTIVE');

-- Seeded administrator from V1 moves to the staff password too.
UPDATE auth_identity.admin_users
   SET password_hash = '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi',
       updated_at    = SYSDATETIMEOFFSET()
 WHERE email = 'diana.admin@bank.com';
