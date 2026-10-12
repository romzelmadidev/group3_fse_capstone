-- ==============================================================================
-- Flyway Migration: V2__merkle_block_anchors.sql
-- Engine: PostgreSQL 16
-- Description: Merkle-Tree Block Anchors for high-throughput cryptographic verification.
-- ==============================================================================

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
