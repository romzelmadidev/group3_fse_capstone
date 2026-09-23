# Day 32 Requirements: Database Schema Design & HikariCP Pool Configuration

## 1. Domain Glossary

- **Master Transactional Database**: The primary Oracle XE / Free relational store holding operational entities including user profiles, bank accounts, balance records, transfer logs, and outbox events.
- **Dedicated Immutable Audit Vault**: The secondary PostgreSQL store holding write-once, append-only records of every ledger mutation event.
- **Strict Numeric Parameter**: Financial decimal representation defined strictly as eighteen total digits with four fractional digits (precision 18, scale 4) for currency amounts.
- **Customer Balance Master**: The core operational record for an account balance, maintaining both ledger balance and held balance.
- **Held Balance**: Balance earmarked for unconfirmed, high-value, or pending maker-checker transactions.
- **Available Balance**: Computed balance representing ledger balance minus held balance.
- **Maker-Checker Workflow**: Dual-control security pattern where one operator initiates a transfer and an independent operator confirms or rejects it.
- **HikariCP Connection Pool**: High-performance JDBC connection pool isolating connection counts, thread queues, and lifecycle boundaries for database interactions.

---

## 2. Scope Boundaries

- **In-Scope**:
  - DDL generation for Oracle XE master storage with strict numeric parameters and check constraints.
  - DDL generation for PostgreSQL audit storage with immutability trigger preventing UPDATE and DELETE.
  - Multi-database Docker Compose orchestration binding both engines to the `banking-net` bridge network.
  - HikariCP connection pool configuration in `application.properties` isolating pool dimensions to `maximum-pool-size=30` and `minimum-idle=5`.
  - Spring Boot multi-datasource configuration classes and JPA entities with matching precision.
  - Automated verification scripts validating table definitions, numeric precision, constraint enforcement, and trigger blocking.
- **Deferred / Future Sprints**:
  - Full Kafka broker consumer loops (Sprint 2 / Day 34).
  - Frontend UI components (Sprint 2 / Day 34–35).
- **Out-of-Scope**:
  - Production cloud infrastructure deployment (AWS / OCI / GCP).
  - External credit bureau API integrations.

---

## 3. Structured Requirements & EARS Acceptance Criteria

### Requirement 1: Master Relational Schema with Strict Numeric Parameters
**User Story**: As a core banking platform architect, I want financial table columns defined with strict numeric precision (18, 4) and non-negative constraints so that currency amounts prevent floating-point drift and avoid negative balances.

- **Ubiquitous Criteria**:
  - `REQ-1.1`: THE master database engine SHALL store all currency amounts using `NUMBER(18, 4)` representing values up to 999,999,999,999,99.9999 PHP.
  - `REQ-1.2`: THE `customer_balance_master` table SHALL enforce non-negative balances via constraint `CHECK (balance_amount >= 0)`.
  - `REQ-1.3`: THE `customer_balance_master` table SHALL enforce non-negative held funds via constraint `CHECK (held_balance >= 0)`.
  - `REQ-1.4`: THE `customer_balance_master` table SHALL enforce the solvency invariant via constraint `CHECK (balance_amount >= held_balance)`.
- **Event-Driven Criteria**:
  - `REQ-1.5`: WHEN an account row is inserted into `customer_balance_master`, THE database SHALL set default values of `0.0000` for both `balance_amount` and `held_balance`.
  - `REQ-1.6`: WHEN a transfer is inserted into `transfers`, THE database SHALL validate that `amount` is strictly greater than zero via constraint `CHECK (amount > 0)`.
- **Unwanted Behavior Criteria**:
  - `REQ-1.7`: IF an operational query attempts to reduce `balance_amount` below `held_balance`, THEN THE database engine SHALL reject the mutation with a constraint violation.
  - `REQ-1.8`: IF a transfer insert provides an amount less than or equal to `0.0000`, THEN THE database engine SHALL reject the transaction with a check constraint violation.

---

### Requirement 2: Dedicated Immutable Audit Vault & Trigger Enforcement
**User Story**: As an internal compliance auditor, I want ledger mutation events stored in an append-only PostgreSQL vault protected by native database triggers so that historical records cannot be modified or deleted.

- **Ubiquitous Criteria**:
  - `REQ-2.1`: THE PostgreSQL audit database SHALL store mutation records in table `ledger_mutation_audit` using numeric precision `NUMERIC(18, 4)` for `mutation_amount`, `before_balance`, and `after_balance`.
  - `REQ-2.2`: THE audit vault SHALL capture `transaction_id`, `account_id`, `mutation_type`, `operator_id`, `operator_role`, `idempotency_key`, `client_ip`, and `audit_timestamp`.
- **Unwanted Behavior Criteria**:
  - `REQ-2.3`: IF an application or administrative user executes an `UPDATE` statement on `ledger_mutation_audit`, THEN THE database trigger SHALL raise exception `'Compliance Violation: ledger_mutation_audit is strictly append-only. UPDATE and DELETE operations are forbidden.'` and abort the command.
  - `REQ-2.4`: IF an application or administrative user executes a `DELETE` statement on `ledger_mutation_audit`, THEN THE database trigger SHALL raise exception `'Compliance Violation: ledger_mutation_audit is strictly append-only. UPDATE and DELETE operations are forbidden.'` and abort the command.

---

### Requirement 3: Multi-Database Docker Container Network Topology
**User Story**: As a DevOps engineer, I want Oracle and PostgreSQL databases deployed via Docker Compose on a unified network so that backend microservices resolve each store through internal DNS names.

- **Ubiquitous Criteria**:
  - `REQ-3.1`: THE container orchestration SHALL define a bridge network named `banking-net`.
  - `REQ-3.2`: THE master database container SHALL be named `oracle-xe-master`, attach to `banking-net`, expose port `1521`, and execute initialization DDL from mounted files during startup.
  - `REQ-3.3`: THE audit database container SHALL be named `postgres-audit-vault`, attach to `banking-net`, expose port `5432`, and execute initialization DDL from mounted files during startup.
- **State-Driven Criteria**:
  - `REQ-3.4`: WHILE both database containers run on `banking-net`, THE containers SHALL maintain network reachability with internal hostnames `oracle-xe-master` and `postgres-audit-vault`.

---

### Requirement 4: HikariCP Thread Pool Dimension Isolation
**User Story**: As a backend performance engineer, I want HikariCP thread pool dimensions configured within `application.properties` to isolate connection pools so that master transaction surges do not deplete audit resources and pool starvation is prevented.

- **Ubiquitous Criteria**:
  - `REQ-4.1`: THE system SHALL configure `hikari.maximum-pool-size=30` for the Oracle master datasource within `application.properties`.
  - `REQ-4.2`: THE system SHALL configure `hikari.minimum-idle=5` for the Oracle master datasource within `application.properties`.
  - `REQ-4.3`: THE system SHALL configure `hikari.maximum-pool-size=30` for the PostgreSQL audit datasource within `application.properties`.
  - `REQ-4.4`: THE system SHALL configure `hikari.minimum-idle=5` for the PostgreSQL audit datasource within `application.properties`.
  - `REQ-4.5`: THE system SHALL assign distinct pool names (`OracleMasterHikariPool` and `PostgresAuditHikariPool`) to prevent cross-datasource metric confusion.
- **State-Driven Criteria**:
  - `REQ-4.6`: WHILE the Spring Boot application is running, THE master datasource and audit datasource SHALL operate through separate `EntityManagerFactory` and `PlatformTransactionManager` beans.
