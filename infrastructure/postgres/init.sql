-- =========================================================================
-- CAPSTONE FSE: Core Retail Ledger & Balance Mutation Engine
-- POSTGRESQL 15+ Immutable Audit Trail DDL & Immutability Trigger
-- =========================================================================

CREATE TABLE IF NOT EXISTS ledger_mutation_audit (
    audit_id            BIGSERIAL PRIMARY KEY,
    transaction_id      VARCHAR(64) NOT NULL UNIQUE,
    account_id          VARCHAR(36) NOT NULL,
    mutation_type       VARCHAR(16) NOT NULL CHECK (mutation_type IN ('DEBIT', 'CREDIT', 'HOLD', 'RELEASE')),
    mutation_amount     NUMERIC(18, 4) NOT NULL CHECK (mutation_amount > 0),
    before_balance      NUMERIC(18, 4) NOT NULL,
    after_balance       NUMERIC(18, 4) NOT NULL,
    initiator_user_id   VARCHAR(36) NOT NULL,
    approved_by_user_id VARCHAR(36),
    status              VARCHAR(20) DEFAULT 'COMMITTED' NOT NULL CHECK (status IN ('COMMITTED', 'FAILED', 'ROLLED_BACK')),
    created_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Fast Indexing for Historical Statement Queries
CREATE INDEX IF NOT EXISTS idx_audit_acc_time ON ledger_mutation_audit(account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_tx_id ON ledger_mutation_audit(transaction_id);

-- =========================================================================
-- IMMUTABILITY ENFORCEMENT TRIGGER (Strict Append-Only Guarantee)
-- =========================================================================
-- Any attempt by application users or DBAs to UPDATE or DELETE an audit line
-- will be immediately blocked with an exception.

CREATE OR REPLACE FUNCTION enforce_audit_immutability()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Compliance Violation: ledger_mutation_audit is strictly append-only. UPDATE and DELETE operations are blocked.'
        USING ERRCODE = '23505';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_immutable_audit ON ledger_mutation_audit;

CREATE TRIGGER trg_immutable_audit
BEFORE UPDATE OR DELETE ON ledger_mutation_audit
FOR EACH ROW
EXECUTE FUNCTION enforce_audit_immutability();

-- =========================================================================
-- STARTER SEED DATA
-- =========================================================================
INSERT INTO ledger_mutation_audit (
    transaction_id, account_id, mutation_type, mutation_amount,
    before_balance, after_balance, initiator_user_id, approved_by_user_id, status, created_at
) VALUES (
    'T5001', 'A2001', 'DEBIT', 2000.0000,
    300000.0000, 298000.0000, 'U1001', NULL, 'COMMITTED', '2024-06-01 14:32:00+08'
);
