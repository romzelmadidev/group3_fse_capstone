-- ==============================================================================
-- Flyway Repeatable Migration: R__enforce_audit_immutability.sql
-- Engine: PostgreSQL 16
-- Description: Declares plpgsql immutability enforcement trigger to strictly
--              block UPDATE and DELETE operations on audit tables.
-- ==============================================================================

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

DROP TRIGGER IF EXISTS trg_immutable_audit_block ON audit_block_anchor;
CREATE TRIGGER trg_immutable_audit_block
    BEFORE UPDATE OR DELETE
ON audit_block_anchor
FOR EACH ROW
EXECUTE FUNCTION enforce_audit_immutability();
