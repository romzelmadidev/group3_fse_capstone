# Day 32 Implementation Tasks: Schema Generation & HikariCP Configuration

This document defines actionable tasks for implementing Day 32 deliverables.

---

## Task Checklist

- [ ] **Task 1: DDL Construction for Oracle XE Master Storage**
  - Path: `infrastructure/oracle/init.sql`
  - Implement tables `users`, `roles`, `user_roles`, `accounts`, `customer_balance_master`, `transfers`, `bills_payment`, `outbox_events`.
  - Enforce strict numeric type `NUMBER(18, 4)` and check constraints (`balance_amount >= held_balance >= 0`, `amount > 0`).
  - Add indexes: `idx_cbm_account`, `idx_transfers_pending`, `idx_transfers_source`, `idx_outbox_pending`.
  - Seed initial roles (`ROLE_CUSTOMER`, `ROLE_LEDGER`, `ROLE_ADMIN`) and test accounts.
  - _Requirements: REQ-1.1, REQ-1.2, REQ-1.3, REQ-1.4, REQ-1.5, REQ-1.6; Design: Section 1, Section 3_

- [ ] **Task 2: DDL Construction for PostgreSQL Immutable Audit Vault**
  - Path: `infrastructure/postgres/init.sql`
  - Implement table `ledger_mutation_audit` with `NUMERIC(18, 4)` precision.
  - Implement PL/pgSQL function `prevent_audit_modification()` raising compliance exception.
  - Bind trigger `trg_no_update_delete_mutation_audit` on `BEFORE UPDATE OR DELETE`.
  - Add performance indexes `idx_audit_tx_id` and `idx_audit_account`.
  - _Requirements: REQ-2.1, REQ-2.2, REQ-2.3, REQ-2.4; Design: Section 1, Section 3_

- [ ] **Task 3: Multi-Database Docker Compose Orchestration**
  - Path: `infrastructure/docker-compose.yml`
  - Configure bridge network `banking-net`.
  - Define container `oracle-xe-master` mounting `./oracle/init.sql` to `/container-entrypoint-initdb.d/init.sql`.
  - Define container `postgres-audit-vault` mounting `./postgres/init.sql` to `/docker-entrypoint-initdb.d/init.sql`.
  - Expose ports `1521` (Oracle) and `5432` (Postgres).
  - _Requirements: REQ-3.1, REQ-3.2, REQ-3.3, REQ-3.4; Design: Section 1_

- [ ] **Task 4: Spring Boot Backend Architecture & Module Setup**
  - Paths:
    - `backend/pom.xml` (Root aggregator)
    - `backend/common-contracts/pom.xml` and shared classes
    - `backend/ledger-mutation-engine/pom.xml`
    - `backend/account-service/pom.xml`
  - Establish Maven project structure with Java 21 and Spring Boot 3.3+.
  - Define shared enums and exception contracts.
  - _Requirements: REQ-4.6; Design: Section 1, Section 2_

- [ ] **Task 5: HikariCP Configuration in `application.properties`**
  - Path: `backend/ledger-mutation-engine/src/main/resources/application.properties`
  - Configure Oracle master pool: `hikari.maximum-pool-size=30`, `hikari.minimum-idle=5`, `pool-name=OracleMasterHikariPool`.
  - Configure PostgreSQL audit pool: `hikari.maximum-pool-size=30`, `hikari.minimum-idle=5`, `pool-name=PostgresAuditHikariPool`.
  - Path: `backend/account-service/src/main/resources/application.properties`
  - Configure account service pool: `hikari.maximum-pool-size=30`, `hikari.minimum-idle=5`.
  - _Requirements: REQ-4.1, REQ-4.2, REQ-4.3, REQ-4.4, REQ-4.5; Design: Section 3_

- [ ] **Task 6: Multi-Datasource Java Configuration & JPA Entities**
  - Paths:
    - `backend/ledger-mutation-engine/src/main/java/com/fse/banking/ledger/config/PrimaryOracleDataSourceConfig.java`
    - `backend/ledger-mutation-engine/src/main/java/com/fse/banking/ledger/config/AuditPostgresDataSourceConfig.java`
    - `backend/ledger-mutation-engine/src/main/java/com/fse/banking/ledger/model/master/` (CustomerBalanceMaster, Transfer, BillsPayment, OutboxEvent)
    - `backend/ledger-mutation-engine/src/main/java/com/fse/banking/ledger/model/audit/` (LedgerMutationAudit)
    - `backend/ledger-mutation-engine/src/main/java/com/fse/banking/ledger/repository/master/` (BalanceMasterRepository)
    - `backend/ledger-mutation-engine/src/main/java/com/fse/banking/ledger/repository/audit/` (LedgerMutationAuditRepository)
  - Bind isolated datasources to separate entity managers and transaction managers.
  - Ensure entity fields use `BigDecimal` with `@Column(precision = 18, scale = 4)`.
  - _Requirements: REQ-1.1, REQ-2.1, REQ-4.6; Design: Section 1, Section 4_

- [ ] **Task 7: Comprehensive Automated Verification Suite**
  - Paths:
    - `testing/scripts/verify_day32.py`
  - Verify Docker containers running on `banking-net`.
  - Validate Oracle tables, columns, numeric precision (18, 4), and check constraints.
  - Validate PostgreSQL tables, numeric precision (18, 4), and trigger immutability rejection on UPDATE/DELETE.
  - Verify HikariCP property settings across `application.properties` files.
  - _Requirements: REQ-1.1 through REQ-4.5; Design: Section 4_
