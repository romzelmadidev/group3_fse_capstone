-- ==============================================================================
-- Flyway Migration: V103__drop_staff_fk_on_transactions.sql
-- Description: Approvers and reversers are staff (admin_users), but these two
--   columns were foreign keys to users (customers). Approving a reversal as a
--   staff checker failed with ORA-02291 on FK_TX_APPROVED_BY. Drop both keys;
--   staff ids are validated in CbsReversalService against v_all_users.
-- ==============================================================================
BEGIN
    FOR c IN (SELECT constraint_name FROM user_constraints
               WHERE table_name = 'TRANSACTIONS'
                 AND constraint_name IN ('FK_TX_APPROVED_BY', 'FK_TX_REVERSED_BY')) LOOP
        EXECUTE IMMEDIATE 'ALTER TABLE transactions DROP CONSTRAINT ' || c.constraint_name;
    END LOOP;
END;
/
