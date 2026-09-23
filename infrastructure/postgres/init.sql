-- ==============================================================================
-- FSE Capstone: Dedicated Immutable Audit Vault (PostgreSQL 16)
-- Database Container: postgres-audit-vault
-- Database: banking_audit
-- User: audit_user
-- ==============================================================================

-- Drop existing table if recreating
DROP TABLE IF EXISTS ledger_mutation_audit CASCADE;

-- 1. Table: ledger_mutation_audit
-- Strict Numeric Precision: NUMERIC(18, 4) with immutable append-only constraints
CREATE TABLE ledger_mutation_audit (
    audit_id             BIGSERIAL PRIMARY KEY,
    transaction_id       VARCHAR(64) UNIQUE NOT NULL,
    account_id           VARCHAR(64) NOT NULL,
    mutation_type        VARCHAR(20) NOT NULL,
    mutation_amount      NUMERIC(18, 4) NOT NULL,
    before_balance       NUMERIC(18, 4) NOT NULL,
    after_balance        NUMERIC(18, 4) NOT NULL,
    initiator_user_id    VARCHAR(64) NOT NULL,
    approved_by_user_id  VARCHAR(64),
    status               VARCHAR(20) DEFAULT 'COMMITTED' NOT NULL,
    created_at           TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_audit_mutation_type CHECK (mutation_type IN ('DEBIT', 'CREDIT', 'HOLD', 'RELEASE')),
    CONSTRAINT chk_audit_status CHECK (status IN ('COMMITTED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT chk_audit_mutation_amount CHECK (mutation_amount > 0),
    CONSTRAINT chk_audit_before_balance CHECK (before_balance >= 0),
    CONSTRAINT chk_audit_after_balance CHECK (after_balance >= 0)
);

-- 2. Compliance Trigger: Enforce Immutability (Reject UPDATE and DELETE)
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Compliance Violation: ledger_mutation_audit is strictly append-only. UPDATE and DELETE operations are forbidden.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_no_update_delete_mutation_audit ON ledger_mutation_audit;

CREATE TRIGGER trg_no_update_delete_mutation_audit
BEFORE UPDATE OR DELETE ON ledger_mutation_audit
FOR EACH ROW EXECUTE FUNCTION prevent_audit_modification();

-- 3. Performance Indexes
CREATE INDEX IF NOT EXISTS idx_audit_account ON ledger_mutation_audit(account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_initiator ON ledger_mutation_audit(initiator_user_id);

-- ==============================================================================
-- Seed Population: Realistic Audit Journal Matching Operational Transactions
-- ==============================================================================

-- Audit Record 1: Opening balance credit for Customer 1
INSERT INTO ledger_mutation_audit (
    transaction_id, account_id, mutation_type, mutation_amount,
    before_balance, after_balance, initiator_user_id, approved_by_user_id, status, created_at
) VALUES (
    'tx-init-cst-001', 'acc-2001-sav-001', 'CREDIT', 25050000.0000,
    0.0000, 25050000.0000, 'usr-1004-adm-001', 'usr-1004-adm-001', 'COMMITTED', CURRENT_TIMESTAMP - INTERVAL '30' DAY
);

-- Audit Record 2: Opening balance credit for Customer 2
INSERT INTO ledger_mutation_audit (
    transaction_id, account_id, mutation_type, mutation_amount,
    before_balance, after_balance, initiator_user_id, approved_by_user_id, status, created_at
) VALUES (
    'tx-init-cst-002', 'acc-2003-sav-002', 'CREDIT', 12345678.1250,
    0.0000, 12345678.1250, 'usr-1004-adm-001', 'usr-1004-adm-001', 'COMMITTED', CURRENT_TIMESTAMP - INTERVAL '25' DAY
);

-- Audit Record 3: OTC Cash Withdrawal (Matches tx-4003-otc-003)
INSERT INTO ledger_mutation_audit (
    transaction_id, account_id, mutation_type, mutation_amount,
    before_balance, after_balance, initiator_user_id, approved_by_user_id, status, created_at
) VALUES (
    'tx-4003-otc-003', 'acc-2001-sav-001', 'DEBIT', 50000.0000,
    25050000.0000, 25000000.0000, 'usr-1001-cst-001', 'usr-1003-tel-001', 'COMMITTED', CURRENT_TIMESTAMP - INTERVAL '2' DAY
);

-- Audit Record 4: Transfer Debit (Matches tx-4002-cmt-002)
INSERT INTO ledger_mutation_audit (
    transaction_id, account_id, mutation_type, mutation_amount,
    before_balance, after_balance, initiator_user_id, approved_by_user_id, status, created_at
) VALUES (
    'tx-4002-cmt-002', 'acc-2002-chk-001', 'DEBIT', 150000.0000,
    8650000.0000, 8500000.0000, 'usr-1001-cst-001', 'usr-1003-tel-001', 'COMMITTED', CURRENT_TIMESTAMP - INTERVAL '1' DAY
);

-- Audit Record 5: Soft Hold Placed for High-Value Maker-Checker Transfer (Matches tx-4001-hld-001)
INSERT INTO ledger_mutation_audit (
    transaction_id, account_id, mutation_type, mutation_amount,
    before_balance, after_balance, initiator_user_id, approved_by_user_id, status, created_at
) VALUES (
    'tx-4001-hld-001', 'acc-2001-sav-001', 'HOLD', 5000000.0000,
    25000000.0000, 20000000.0000, 'usr-1001-cst-001', NULL, 'COMMITTED', CURRENT_TIMESTAMP - INTERVAL '1' HOUR
);
