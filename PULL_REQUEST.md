# Pull Request: Day 32 Architecture, Dual-Database Schemas, HikariCP Isolation, and Project Skeletons

## Target Branch
- Source: `jm-branch`
- Target: `main`

---

## Overview

This pull request completes the deliverables for **Day 32: FSE Capstone - Design & Schema Generation**. 

It sets up the containerized dual-database infrastructure, creates the database tables using strict numeric parameters (`NUMBER(18, 4)` and `NUMERIC(18, 4)`), configures isolated HikariCP connection pools in `application.properties`, establishes the team architectural source of truth in `README.md`, and scaffolds clean multi-module project skeletons without premature implementation code.

---

## Summary of Changes

### 1. Master Operational Database (Oracle Database XE 21c)
- **Container**: `oracle-xe-master` running `gvenzl/oracle-xe:latest` on port `1521` (Service: `XEPDB1`, User: `fse_user`).
- **Initialization Script**: [`infrastructure/oracle/init.sql`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/infrastructure/oracle/init.sql).
- **Canonical Tables Created**:
  1. `users`: Customer, teller, and administrator identities, password and PIN hashes, concurrent session counters, and account statuses.
  2. `accounts`: Savings, checking, and credit accounts linked to users with unique account numbers.
  3. `balance_master`: Account ledger state (`balance_amount`, `hold_amount`, `available_balance`) with strict constraint `CHECK (balance_amount >= hold_amount)`. Designed for row-level pessimistic locking (`SELECT ... FOR UPDATE`).
  4. `credit_assessments`: Collateral market and appraised values, credit score bounds (300 to 850), approved limits, and risk tiers.
  5. `transactions`: Transaction records, before/after balances, Maker-Checker flags (`requires_maker_checker`), and approver IDs.
  6. `outbox_events`: Transactional outbox table (`aggregate_type`, `aggregate_id`, `event_type`, `kafka_topic`, `payload`, `status`, `retry_count`) for reliable event publishing.
  7. `notifications`: Alert records for transaction events, security updates, and Maker-Checker escalations.
- **Session and Token Architecture**: Removed relational `auth_sessions` table in favor of Redis-based token storage and session eviction.
- **Seeded Dataset**: 4 users, 4 accounts, 4 balance records, 1 credit assessment, 3 transactions, 2 outbox events, and 2 notifications.

### 2. Dedicated Immutable Audit Vault (PostgreSQL 16)
- **Container**: `postgres-audit-vault` running `postgres:16-alpine` on port `5432` (Database: `banking_audit`, User: `audit_user`).
- **Initialization Script**: [`infrastructure/postgres/init.sql`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/infrastructure/postgres/init.sql).
- **Core Table**: `ledger_mutation_audit` with strict precision `NUMERIC(18, 4)`.
- **Compliance Enforcement**: Attached trigger `trg_no_update_delete_mutation_audit` invoking function `prevent_audit_modification()` to block all `UPDATE` and `DELETE` queries at the database engine level.
- **Seeded Dataset**: 5 immutable audit log records matching the initial transaction history.

### 3. Redis In-Memory Token and Session Store (Redis 7)
- Key patterns established in architecture documents for sub-millisecond gateway validation:
  - `auth:token:{jti}`: Active access token metadata with 15-minute TTL.
  - `auth:user-sessions:{userId}`: Active session token set enforcing `max_concurrent_sessions`.
  - `auth:blacklist:{jti}`: Immediate revocation blacklist checked by the API gateway.
  - `idemp:{idempotencyKey}`: 60-second atomic lock (`SET NX EX 60`) preventing duplicate mutations.

### 4. Connection Pool Dimensions (HikariCP)
Configured isolated thread pool dimensions in `application.properties`:
- `backend/ledger-mutation-engine/src/main/resources/application.properties`:
  - `OracleMasterHikariPool`: `maximum-pool-size=30`, `minimum-idle=5`, targeting `jdbc:oracle:thin:@//localhost:1521/XEPDB1`.
  - `PostgresAuditHikariPool`: `maximum-pool-size=30`, `minimum-idle=5`, targeting `jdbc:postgresql://localhost:5432/banking_audit`.
- `backend/account-service/src/main/resources/application.properties`:
  - `AccountServiceHikariPool`: `maximum-pool-size=30`, `minimum-idle=5`.

### 5. Template Folders and Project Skeletons
- Maintained clean folder structures for backend multi-module Maven services:
  - `backend/account-service/src/main/java/.gitkeep`
  - `backend/common-contracts/src/main/java/.gitkeep`
  - `backend/ledger-mutation-engine/src/main/java/.gitkeep`
- Maintained frontend template folder:
  - `frontend/src/.gitkeep`
- All premature business and application code has been removed. Coding will commence in subsequent implementation phases following the team task plan.

### 6. Architectural Source of Truth and Team Documentation
- [`README.md`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/README.md): Master team source of truth covering architecture topology, complete networking matrix, full DDL schemas, HikariCP parameters, and Docker quick start.
- [`ERD.md`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/ERD.md): Detailed Mermaid entity-relationship diagram, DDL definitions, and Redis key schemas.
- [`ARCHITECTURE.md`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/ARCHITECTURE.md): Service-by-service functional breakdown, API gateway routing, and locking strategies.
- [`API_SPECIFICATION.md`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/API_SPECIFICATION.md): Endpoint contracts, request/response structures, and RFC-7807 error models.
- [`JIRA_BACKLOG.md`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/JIRA_BACKLOG.md): Sprint epics and EARS-compliant acceptance criteria.
- [`specs/`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/specs): Spec-Driven Development documents (`day32_requirements.md`, `day32_design.md`, `day32_tasks.md`).

---

## Verification and Testing

### 1. Docker Container Status
Both database containers run concurrently on bridge network `banking-net`:
```powershell
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```
Verification result:
- `oracle-xe-master`: Up and healthy on `0.0.0.0:1521->1521/tcp`
- `postgres-audit-vault`: Up and healthy on `0.0.0.0:5432->5432/tcp`

### 2. Oracle XE Schema and Seed Verification
Executed SQL query inside `oracle-xe-master`:
```sql
SELECT 'USERS' AS tbl, count(*) FROM users
UNION ALL SELECT 'ACCOUNTS', count(*) FROM accounts
UNION ALL SELECT 'BALANCE_MASTER', count(*) FROM balance_master
UNION ALL SELECT 'CREDIT_ASSESSMENTS', count(*) FROM credit_assessments
UNION ALL SELECT 'TRANSACTIONS', count(*) FROM transactions
UNION ALL SELECT 'OUTBOX_EVENTS', count(*) FROM outbox_events
UNION ALL SELECT 'NOTIFICATIONS', count(*) FROM notifications;
```
Result: 7 tables present and correctly populated. `auth_sessions` dropped.

### 3. PostgreSQL Audit Immutability Verification
1. Count verification: 5 initial audit rows in `ledger_mutation_audit`.
2. Immutability verification: Executing `DELETE FROM ledger_mutation_audit;` or `UPDATE ledger_mutation_audit SET status = 'FAILED';` throws exception:
   `Compliance Violation: ledger_mutation_audit is strictly append-only. UPDATE and DELETE operations are forbidden.`

---

## Checklist
- [x] Strict numeric precision applied across all tables (`NUMBER(18, 4)` and `NUMERIC(18, 4)`).
- [x] Oracle XE 21c and PostgreSQL 16 containers running and healthy.
- [x] Relational auth table removed; Redis token architecture documented.
- [x] Isolated HikariCP connection pools configured (`maximum-pool-size=30`, `minimum-idle=5`).
- [x] Application code cleared, leaving clean directory skeletons with `.gitkeep`.
- [x] README and architectural specifications updated as the team source of truth.
- [x] Working tree clean and pushed to branch `jm-branch`.
