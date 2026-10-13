-- ==============================================================================
-- Flyway Migration: V101__separate_staff_and_customer_credentials.sql
-- Engine: Oracle Database 21c / 23ai
-- Description: Bank staff (admin_users) and retail customers (users) must be
--              different identities with different credentials. Before this
--              migration the team members existed in BOTH tables with the same
--              id, email and password hash, and V99 re-seeded staff into users.
--
--   1. Team customer profiles stay in users (they own the demo savings accounts).
--   2. Staff-role rows left in users are copied into admin_users, then removed.
--   3. Team staff identities are re-keyed:  USR-TM-WAX / wax@bank.com
--                                      ->  ADM-TM-WAX / wax.staff@bank.com
--   4. Staff move off the shared demo password hash onto a staff-only one.
--
-- Versioned above V99 so it also runs after the dev seed. Safe to re-run.
-- Staff demo password: Staff@Aura2026   (customers keep their own passwords)
-- ==============================================================================

-- 1. Team members are customers in users, never staff.
UPDATE users
   SET role = 'CUSTOMER'
 WHERE user_id LIKE 'USR-TM-%'
   AND role <> 'CUSTOMER';

-- 2a. Preserve any staff-only account that exists only in users (for example the
--     seeded teller usr-1003-tel-001) by copying it into admin_users.
INSERT INTO admin_users (
    admin_id, first_name, middle_name, last_name, email, phone_number,
    dob, government_id, role, password_hash,
    max_concurrent_sessions, failed_login_attempts, status,
    last_login_at, created_at, updated_at
)
SELECT
    u.user_id, u.first_name, u.middle_name, u.last_name, u.email, u.phone_number,
    u.dob, u.government_id, u.role, u.password_hash,
    u.max_concurrent_sessions, u.failed_login_attempts, u.status,
    u.last_login_at, u.created_at, u.updated_at
FROM users u
WHERE u.role IN ('ADMIN', 'TELLER', 'MANAGER')
  AND u.user_id NOT IN (SELECT admin_id FROM admin_users)
  AND u.email NOT IN (SELECT email FROM admin_users);

-- 3. Re-key the team staff identities so they no longer match the customer ones.
DECLARE
    TYPE t_names IS TABLE OF VARCHAR2(20);
    v_names t_names := t_names('WAX', 'HANS', 'JM', 'ZEL', 'JESSY', 'MAYE', 'ANGEL');
BEGIN
    FOR i IN 1 .. v_names.COUNT LOOP
        UPDATE admin_users
           SET admin_id   = 'ADM-TM-' || v_names(i),
               email      = LOWER(v_names(i)) || '.staff@bank.com',
               updated_at = CURRENT_TIMESTAMP
         WHERE admin_id = 'USR-TM-' || v_names(i);
    END LOOP;
END;
/

-- 4. Staff-only password hash (replaces the demo hash every customer also uses).
UPDATE admin_users
   SET password_hash = '$2a$10$8gTnXmq9P23IKNeX9V85xOBHTykyhhBv6kGaqPY3g/yXMZCgZFWgi',
       updated_at    = CURRENT_TIMESTAMP
 WHERE password_hash = '$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC';

-- 5. Remove staff-role rows from users. A row that still owns accounts or other
--    child records is skipped; the application refuses to sign in any staff-role
--    row on a customer channel regardless.
BEGIN
    FOR r IN (SELECT user_id FROM users WHERE role IN ('ADMIN', 'TELLER', 'MANAGER')) LOOP
        BEGIN
            DELETE FROM users WHERE user_id = r.user_id;
        EXCEPTION
            WHEN OTHERS THEN
                IF SQLCODE != -2292 THEN
                    RAISE;
                END IF;
        END;
    END LOOP;
END;
/

COMMIT;
