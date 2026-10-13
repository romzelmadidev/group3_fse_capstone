-- ==============================================================================
-- Flyway Migration: V3__failed_transaction_replay_status_index.sql
-- Engine: PostgreSQL 16
-- Description: Composite index on (replay_status, failure_timestamp DESC) for
--              high-performance constrained DLQ replay queries under ShedLock.
-- ==============================================================================

CREATE INDEX IF NOT EXISTS idx_failed_tx_replay_status_time 
ON failed_transaction_audit (replay_status, failure_timestamp DESC);
