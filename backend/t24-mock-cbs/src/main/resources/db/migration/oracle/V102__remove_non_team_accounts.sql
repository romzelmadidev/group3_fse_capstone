-- ==============================================================================
-- Flyway Migration: V102__remove_non_team_accounts.sql
-- Description: Keep only team accounts (angel, wax, jm, hans, jessy, maye, zel).
--   Removes demo/test customers and staff plus the accounts, balances and
--   transactions they own. The interbank nostro system user is infrastructure
--   and is kept. Idempotent: running it again deletes nothing.
--   Child rows go first; every subquery still sees its parent rows because the
--   parents (accounts, users) are deleted last.
-- ==============================================================================

DELETE FROM reversal_requests WHERE original_tx_id IN (
    SELECT transaction_id FROM transactions
     WHERE from_account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
               SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'))
        OR to_account_id   IN (SELECT account_id FROM accounts WHERE user_id IN (
               SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro')));

DELETE FROM transaction_status_history WHERE transaction_id IN (
    SELECT transaction_id FROM transactions
     WHERE from_account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
               SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'))
        OR to_account_id   IN (SELECT account_id FROM accounts WHERE user_id IN (
               SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro')));

DELETE FROM gl_ledger WHERE transaction_id IN (
    SELECT transaction_id FROM transactions
     WHERE from_account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
               SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'))
        OR to_account_id   IN (SELECT account_id FROM accounts WHERE user_id IN (
               SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro')));

DELETE FROM transactions
 WHERE from_account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
           SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'))
    OR to_account_id   IN (SELECT account_id FROM accounts WHERE user_id IN (
           SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'));

DELETE FROM balance_master WHERE account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'));
DELETE FROM eod_balance_snapshots WHERE account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'));
DELETE FROM interest_accruals WHERE account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'));
DELETE FROM uncollected_fees WHERE account_id IN (SELECT account_id FROM accounts WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro'));
DELETE FROM accounts WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro');

DELETE FROM kyc_submissions WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro');
DELETE FROM notifications WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro');
DELETE FROM device_push_tokens WHERE user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro');
-- Surviving transactions may name a removed user as approver/reverser.
UPDATE transactions SET approved_by_user_id = NULL WHERE approved_by_user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro');
UPDATE transactions SET reversed_by_user_id = NULL WHERE reversed_by_user_id IN (
    SELECT user_id FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro');
DELETE FROM users WHERE user_id NOT LIKE 'USR-TM-%' AND user_id <> 'usr-system-nostro';

-- Staff: team members only (admin ids are ADM-TM-<NAME>).
DELETE FROM admin_users WHERE admin_id NOT LIKE 'ADM-TM-%';

COMMIT;
