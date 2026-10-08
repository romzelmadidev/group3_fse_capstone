-- ============================================================
-- PostgreSQL Audit Database
-- Core Retail Ledger & Balance Mutation Engine
-- ============================================================

-- ============================================================
-- TABLE: ledger_mutation_audit
-- Immutable Audit Trail Store
-- ============================================================

CREATE TABLE IF NOT EXISTS ledger_mutation_audit (

    audit_id BIGSERIAL PRIMARY KEY,

    transaction_id VARCHAR(64) NOT NULL,

    account_id VARCHAR(36) NOT NULL,

    mutation_type VARCHAR(16) NOT NULL,

    mutation_amount NUMERIC(18,4) NOT NULL,

    before_balance NUMERIC(18,4) NOT NULL,

    after_balance NUMERIC(18,4) NOT NULL,

    initiator_user_id VARCHAR(36) NOT NULL,

    approved_by_user_id VARCHAR(36),

    status VARCHAR(20) NOT NULL DEFAULT 'COMMITTED',

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    sha256_hash VARCHAR(64),

    prev_hash VARCHAR(64),

    CONSTRAINT uq_audit_tx_leg
    UNIQUE (transaction_id, account_id, mutation_type),

    CONSTRAINT ledger_mutation_audit_mutation_amount_check
    CHECK (mutation_amount > 0),

    CONSTRAINT ledger_mutation_audit_after_balance_check
    CHECK (after_balance >= 0),

    CONSTRAINT ledger_mutation_audit_mutation_type_check
    CHECK (
              mutation_type IN (
              'TRANSFER',
              'DEBIT',
              'CREDIT',
              'HOLD',
              'RELEASE',
              'REVERSAL'
                               )
    ),

    CONSTRAINT ledger_mutation_audit_status_check
    CHECK (
              status IN (
              'COMMITTED',
              'FAILED',
              'ROLLED_BACK'
                        )
    )
    );

-- ============================================================
-- PERFORMANCE INDEXES
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_audit_tx_id
    ON ledger_mutation_audit(transaction_id);

CREATE INDEX IF NOT EXISTS idx_audit_acc_time
    ON ledger_mutation_audit(account_id, created_at DESC);

-- ============================================================
-- TABLE: failed_transaction_audit
-- Dead Letter Queue (DLQ) Incident Ingestion
-- ============================================================
CREATE TABLE IF NOT EXISTS failed_transaction_audit (
    incident_id VARCHAR(64) PRIMARY KEY,
    correlation_id VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    error_type VARCHAR(100) NOT NULL,
    error_code VARCHAR(50) NOT NULL,
    circuit_breaker_state VARCHAR(20) NOT NULL,
    payload_json TEXT NOT NULL,
    stack_trace TEXT,
    replay_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REPLAY',
    failure_timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_dlq_incident_status ON failed_transaction_audit(replay_status, failure_timestamp DESC);

-- ============================================================
-- TABLE: eod_reports_metadata
-- Cryptographic Registry of Generated EOD Artifacts
-- ============================================================
CREATE TABLE IF NOT EXISTS eod_reports_metadata (
    report_id VARCHAR(64) PRIMARY KEY,
    report_type VARCHAR(50) NOT NULL,
    business_date VARCHAR(20) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    storage_uri VARCHAR(500) NOT NULL,
    sha256_checksum VARCHAR(64) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    record_count BIGINT NOT NULL,
    generated_at_utc TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retention_expiry_date TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_eod_reports_date ON eod_reports_metadata(business_date);

-- ============================================================
-- TABLE: compliance_filings
-- AMLA CTR & STR Regulatory Filings
-- ============================================================
CREATE TABLE IF NOT EXISTS compliance_filings (
    filing_id VARCHAR(64) PRIMARY KEY,
    filing_type VARCHAR(30) NOT NULL,
    business_date VARCHAR(20) NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    payload_xml TEXT NOT NULL,
    amlc_ref VARCHAR(64) NOT NULL,
    filed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_amla_filing_date ON compliance_filings(business_date, filing_type);

-- ============================================================
-- TABLE: transaction_status_audit
-- Hash-Chained Canonical Status Change Audit Log
-- ============================================================
CREATE TABLE IF NOT EXISTS transaction_status_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    change_reason VARCHAR(100) NOT NULL,
    reason_details VARCHAR(500),
    actor_id VARCHAR(64) NOT NULL,
    actor_type VARCHAR(30) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata_json TEXT,
    prev_hash VARCHAR(64) NOT NULL,
    sha256_hash VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_status_audit_tx ON transaction_status_audit(transaction_id, changed_at ASC);

-- ============================================================
-- IMMUTABILITY FUNCTION & TRIGGERS
-- Prevent UPDATE and DELETE
-- ============================================================

CREATE OR REPLACE FUNCTION enforce_audit_immutability()
RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION
    'Compliance Violation: Audit records are strictly append-only. UPDATE and DELETE operations are blocked.';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_immutable_audit ON ledger_mutation_audit;
CREATE TRIGGER trg_immutable_audit
    BEFORE UPDATE OR DELETE
ON ledger_mutation_audit
FOR EACH ROW
EXECUTE FUNCTION enforce_audit_immutability();

DROP TRIGGER IF EXISTS trg_immutable_status_audit ON transaction_status_audit;
CREATE TRIGGER trg_immutable_status_audit
    BEFORE UPDATE OR DELETE
ON transaction_status_audit
FOR EACH ROW
EXECUTE FUNCTION enforce_audit_immutability();

-- ============================================================
-- TABLE: audit_block_anchor
-- Merkle-Tree Block Anchors for High-Throughput Verification
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_block_anchor (
    block_id BIGSERIAL PRIMARY KEY,
    block_number BIGINT NOT NULL UNIQUE,
    start_audit_id BIGINT NOT NULL,
    end_audit_id BIGINT NOT NULL,
    record_count INT NOT NULL,
    merkle_root VARCHAR(64) NOT NULL,
    prev_block_root VARCHAR(64) NOT NULL,
    block_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_block_number ON audit_block_anchor(block_number);
CREATE INDEX IF NOT EXISTS idx_audit_block_range ON audit_block_anchor(start_audit_id, end_audit_id);

DROP TRIGGER IF EXISTS trg_immutable_audit_block ON audit_block_anchor;
CREATE TRIGGER trg_immutable_audit_block
    BEFORE UPDATE OR DELETE
ON audit_block_anchor
FOR EACH ROW
EXECUTE FUNCTION enforce_audit_immutability();

-- ============================================================
-- SAMPLE SEED DATA
-- ============================================================

INSERT INTO ledger_mutation_audit (
    transaction_id,
    account_id,
    mutation_type,
    mutation_amount,
    before_balance,
    after_balance,
    initiator_user_id,
    approved_by_user_id,
    status,
    created_at,
    prev_hash,
    sha256_hash
)
VALUES (
           'T5001',
           'A2001',
           'TRANSFER',
           2000.0000,
           300000.0000,
           298000.0000,
           'U1001',
           NULL,
           'COMMITTED',
           '2024-06-01 14:32:00',
           'GENESIS_0000000000000000000000000000000000000000000000000000000000000000',
           '4e7c1f8a29b40c812739481239841029348102934810293481029348102933b9'
       )
    ON CONFLICT (transaction_id, account_id, mutation_type) DO NOTHING;

