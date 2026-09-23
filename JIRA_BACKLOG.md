# JIRA Backlog & Epic Story Point Estimation

This document defines the engineering sprint backlog, epic breakdown, formal EARS acceptance criteria, story point estimates (Fibonacci scale: 1, 2, 3, 5, 8, 13), and developer allocations.

The formatted, auto-styled Excel file is available at [`jira_backlog_fse_capstone.xlsx`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/jira_backlog_fse_capstone.xlsx).

---

## 1. Epic Overview & Velocity Allocation

| Epic ID | Epic Name | Story Count | Total Points | Lead Developer Role | Target Sprint Phase |
| :--- | :--- | :---: | :---: | :--- | :--- |
| **EPIC-1** | Perimeter Security & Gateway | 3 | 11 | Backend Eng (Auth & Account) | Day 34 (Sprint 2) |
| **EPIC-2** | Account Management & KYC | 3 | 11 | Backend Eng (Auth & Account) | Day 31–33 (Contract & Sprint 1) |
| **EPIC-3** | Core Mutation & Lock Mechanics | 3 | 18 | Backend Eng (Core / Dual-Write) | Day 31–33 (Sprint 1) |
| **EPIC-4** | Dual-Write Storage & Auditing | 3 | 18 | Backend Eng (Core / Dual-Write) | Day 32–33 (Schema & Sprint 1) |
| **EPIC-5** | Maker-Checker Transfer Engine | 3 | 16 | Backend Eng (Core / Dual-Write) | Day 33–34 (Sprint 1 & 2) |
| **EPIC-6** | Event-Driven Kafka & Projections | 2 | 6 | Backend Eng (Core / Event Streaming) | Day 34 (Sprint 2) |
| **EPIC-7** | Frontend Web Portal (SPA) | 3 | 15 | Frontend Engineer | Day 34–35 (Sprint 2 & Hardening) |
| **EPIC-8** | Testing, Quality & CI/CD | 3 | 18 | DevOps / QA Engineer | Day 32, 35–36 (Schema & Demo Prep) |
| **Total** | **8 Epics** | **23** | **113 Points** | **4 Core Roles** | **Days 31–37** |

---

## 2. Detailed Backlog Items

### Epic 1: Perimeter Security & Gateway
- **Lead Role**: Backend Engineer (Auth & Account)

#### `SEC-101`: Stateless Spring Security with JWT Validation Filter (5 Points)
- **User Story**: As an API client, I want inbound requests verified via stateless JWT so that only authenticated callers reach internal microservice endpoints.
- **EARS Acceptance Criteria**:
  - `THE system SHALL authenticate inbound HTTP requests using HMAC-SHA256 or RSA JWT tokens.`
  - `IF an HTTP request lacks a Bearer token or contains an expired signature, THEN THE gateway SHALL return HTTP 401 Unauthorized using RFC-7807 Problem Details.`

#### `SEC-102`: Redis Token Family Session Store & Refresh Token Rotation (3 Points)
- **User Story**: As a security administrator, I want single-use refresh token rotation and token family tracking in Redis so that stolen or replayed credentials automatically terminate the user session.
- **EARS Acceptance Criteria**:
  - `WHEN a token lookup or refresh request arrives, THE Redis filter SHALL return validation status within 5ms.`
  - `WHEN an authenticated client presents a valid refresh token cookie at /api/v1/auth/refresh, THE system SHALL revoke the current token, issue a rotated single-use refresh token, and return a 15-minute access JWT.`
  - `IF a revoked or previously consumed refresh token is presented at /api/v1/auth/refresh, THEN THE system SHALL detect a replay breach, immediately delete all tokens in that session family from Redis, and return HTTP 401 Unauthorized.`
  - `IF an access token signature exists in the blacklist set, THEN THE system SHALL reject the request with HTTP 401 Unauthorized.`

#### `SEC-103`: Role-Based Access Control (RBAC) Enforcement (3 Points)
- **User Story**: As a platform architect, I want strict path-based role enforcement so that customers cannot execute administrative or teller operations.
- **EARS Acceptance Criteria**:
  - `WHEN a CUSTOMER attempts to call /api/v1/transfers/pending or /api/v1/transfers/{id}/approve, THE gateway SHALL reject the request with HTTP 403 Forbidden.`
  - `THE system SHALL grant access to pending review queues exclusively to users holding the ROLE_LEDGER authority.`

---

### Epic 2: Account Management & KYC
- **Lead Role**: Backend Engineer (Auth & Account)

#### `ACC-201`: Customer Registration & KYC Onboarding Endpoint (5 Points)
- **User Story**: As a new user, I want to submit personal and government ID details so that a verified banking profile can be opened.
- **EARS Acceptance Criteria**:
  - `THE system SHALL validate customer registration fields via JSR-380 annotations.`
  - `IF an email or government ID already exists, THEN THE system SHALL return HTTP 409 Conflict.`
  - `THE system SHALL assign an initial KYC status of PENDING until administrative review.`

#### `ACC-202`: Bank Account Provisioning (Savings, Checking, Credit) (3 Points)
- **User Story**: As an admin, I want to provision specific bank accounts for verified customers so that they can hold funds and initiate transfers.
- **EARS Acceptance Criteria**:
  - `WHEN an Admin approves a customer profile, THE system SHALL generate a unique 12-digit account number.`
  - `THE system SHALL initialize balance_amount to 0.0000 PHP with precision NUMBER(18, 4) in the customer_balance_master table.`

#### `ACC-203`: Account Balance & Profile Lookup with Redis Read Cache (3 Points)
- **User Story**: As a customer, I want to view my current, held, and available balance with near-instant responsiveness.
- **EARS Acceptance Criteria**:
  - `WHEN an account balance query is executed, THE system SHALL first inspect the Redis cache.`
  - `IF the cache misses, THEN THE system SHALL query Oracle XE and populate Redis with a 60-second time-to-live.`

---

### Epic 3: Core Balance Mutation & Lock Mechanics
- **Lead Role**: Backend Engineer (Core / Dual-Write)

#### `MUT-301`: JSR-380 Strict Validation & RFC-7807 Global Exception Handling (5 Points)
- **User Story**: As a financial engine, I want incoming payloads rigorously validated so that invalid numeric formats or malformed JSON never touch the persistence layer.
- **EARS Acceptance Criteria**:
  - `THE system SHALL validate mutation_amount using @Digits(integer=14, fraction=4) and @Positive.`
  - `IF any payload violates schema constraints or arrives as unparseable JSON, THEN THE global @ControllerAdvice handler SHALL generate an RFC-7807 Problem Details response containing specific field fault arrays.`

#### `MUT-302`: Pessimistic Row Locking via Spring Data JPA (@Lock) (8 Points)
- **User Story**: As a system architect, I want balance retrieval protected by database row locks so that concurrent debit attempts cannot cause negative balances.
- **EARS Acceptance Criteria**:
  - `WHEN a balance mutation is executed, THE repository SHALL invoke findByAccountIdForUpdate annotated with @Lock(LockModeType.PESSIMISTIC_WRITE).`
  - `WHILE an account row is locked, CONCURRENT transactions attempting to lock the same account SHALL stall at the database persistence barrier until the holding transaction commits or rolls back.`

#### `MUT-303`: Atomic Balance Mutation with Overdraft Prevention (5 Points)
- **User Story**: As an account holder, I want debit requests aborted if funds are insufficient so that my account never drops below 0.0000 PHP.
- **EARS Acceptance Criteria**:
  - `IF requested debit amount exceeds available_balance, THEN THE system SHALL abort the transaction, release locks, and return HTTP 422 with error code INSUFFICIENT_FUNDS.`
  - `THE system SHALL guarantee that the resulting balance precision remains exactly 4 decimal places.`

---

### Epic 4: Dual-Write Storage & Immutable Auditing
- **Lead Role**: Backend Engineer (Core / Dual-Write)

#### `DUR-401`: Multi-Datasource Spring Configuration (Oracle XE + PostgreSQL) (5 Points)
- **User Story**: As a backend developer, I want two isolated DataSource configurations in Spring Boot so that master balance operations and audit logging use separate connection pools.
- **EARS Acceptance Criteria**:
  - `THE system SHALL initialize PrimaryDataSource (Oracle XE) and AuditDataSource (PostgreSQL 15+) with separate HikariCP pools (maximum-pool-size=30, minimum-idle=5).`
  - `THE system SHALL bind distinct EntityManagerFactory beans to each datasource without cross-context contamination.`

#### `DUR-402`: Immutable Append-Only Audit Logging in PostgreSQL (5 Points)
- **User Story**: As a compliance auditor, I want every state mutation permanently recorded in an append-only table so that balance changes can be verified chronologically.
- **EARS Acceptance Criteria**:
  - `THE system SHALL insert an audit transaction line into ledger_mutation_audit containing before_balance, after_balance, operator identity, and timestamp.`
  - `THE ledger_mutation_audit table SHALL accept only INSERT and SELECT operations, strictly blocking UPDATE and DELETE via database triggers and role permissions.`

#### `DUR-403`: Data Drift Remediation & Atomic Rollback on Audit Failure (8 Points)
- **User Story**: As an auditor, I want un-audited state changes blocked so that a failure in the audit database never leaves an untracked master balance change.
- **EARS Acceptance Criteria**:
  - `IF PostgreSQL drops offline or an audit insert fails mid-transaction, THEN THE system SHALL trigger a complete rollback of the Oracle XE balance update.`
  - `WHEN an audit write fails, THE system SHALL throw a checked LedgerPersistenceException and return HTTP 500.`

---

### Epic 5: Maker-Checker Transfer Engine
- **Lead Role**: Backend Engineer (Core / Dual-Write)

#### `TRX-501`: Funds Transfer Initiation with Threshold Route Evaluation (8 Points)
- **User Story**: As a customer, I want to initiate funds transfers with automatic threshold routing so that small transfers settle immediately and large transfers are held for review.
- **EARS Acceptance Criteria**:
  - `WHEN a transfer amount is <= 10,000,000.0000 PHP, THE system SHALL mutate balances immediately within a single atomic transaction.`
  - `WHEN a transfer amount is > 10,000,000.0000 PHP, THE system SHALL apply a soft hold on the source account (held_balance += amount) and set status to PENDING_APPROVAL.`

#### `TRX-502`: Teller (Ledger) Approval / Rejection Mechanics & Hold Release (5 Points)
- **User Story**: As a Teller (Ledger checker), I want to review pending transfers so that verified high-value transactions can be safely settled.
- **EARS Acceptance Criteria**:
  - `WHEN a Ledger user approves a transfer, THE system SHALL deduct held_balance and current_balance on the source, credit the destination, set status to EXECUTED, and log the Checker's identity.`
  - `WHEN a Ledger user rejects a transfer, THE system SHALL decrement held_balance on the source, set status to REJECTED, and record the rejection reason.`

#### `TRX-503`: Segregation of Duties Enforcement (3 Points)
- **User Story**: As a risk officer, I want makers prevented from approving their own transfers so that internal fraud is systematically blocked.
- **EARS Acceptance Criteria**:
  - `IF the user attempting to approve a transfer is equal to maker_user_id, THEN THE system SHALL reject the approval with HTTP 403 Forbidden under code SEGREGATION_OF_DUTIES_VIOLATION.`

---

### Epic 6: Event-Driven Kafka Streaming & Projections
- **Lead Role**: Backend Engineer (Core / Event Streaming)

#### `EVT-601`: Transactional Outbox & Apache Kafka Publisher (3 Points)
- **User Story**: As a backend service, I want transfer commands persisted via the Transactional Outbox pattern and published to Kafka so that client ingestion is decoupled from synchronous database locking.
- **EARS Acceptance Criteria**:
  - `WHEN a transfer is submitted, THE system SHALL persist the transfer and outbox record within a single Oracle transaction and return HTTP 202 Accepted within 15ms.`
  - `WHEN the outbox worker executes, THE system SHALL publish records to banking.transfers.commands partitioned by source_account_id using producer idempotency (acks=all).`

#### `EVT-602`: Kafka Notification & Audit Projection Consumers (3 Points)
- **User Story**: As an engineering team, I want independent Kafka consumer groups for audit logging and alerts so that downstream operations execute asynchronously without impacting transfer throughput.
- **EARS Acceptance Criteria**:
  - `WHEN a TransferExecuted event arrives on banking.transfers.events, THE audit consumer group SHALL project the record into PostgreSQL ledger_mutation_audit.`
  - `WHEN a transfer state event arrives on banking.transfers.events, THE notification consumer group SHALL format the receipt and dispatch customer alerts without blocking core persistence.`

---

### Epic 7: Frontend Banking Portal (React SPA)
- **Lead Role**: Frontend Engineer

#### `UI-701`: Authentication & Role-Based Navigation Routing (5 Points)
- **User Story**: As a web user, I want a modern login portal that adapts its navigation based on my user role (Customer, Teller, Admin).
- **EARS Acceptance Criteria**:
  - `THE frontend SHALL decode JWT claims and render Customer views for customer accounts, review queue for Ledger accounts, and KYC tables for Admin accounts.`

#### `UI-702`: Customer Funds Transfer & Balance Dashboard (5 Points)
- **User Story**: As a customer, I want an intuitive dashboard to view real-time balances, submit transfer requests, and inspect transaction receipts.
- **EARS Acceptance Criteria**:
  - `THE transfer form SHALL validate numeric precision (4 decimal places) before submission.`
  - `THE UI SHALL display transaction receipts showing before balance, debited amount, and resulting balance.`

#### `UI-703`: Teller / Ledger Maker-Checker Review Queue Dashboard (5 Points)
- **User Story**: As a Teller, I want a dedicated console displaying pending transactions with one-click approval and rejection actions.
- **EARS Acceptance Criteria**:
  - `THE review console SHALL render pending requests with amount, source, destination, and elapsed time.`
  - `THE UI SHALL disable the approval action if the currently logged-in user is the initiator of the transfer.`

---

### Epic 8: Testing, Quality & CI/CD
- **Lead Role**: DevOps / QA Engineer

#### `OPS-801`: Docker Compose Infrastructure Orchestration (5 Points)
- **User Story**: As a developer, I want a single docker-compose command to launch all infrastructure services with health checks and persistence.
- **EARS Acceptance Criteria**:
  - `THE docker-compose file SHALL boot Oracle XE 21c, PostgreSQL 15+, Redis, RabbitMQ, Prometheus, and Grafana in a unified bridge network.`

#### `OPS-802`: Apache JMeter Concurrent Stress Testing Suite (8 Points)
- **User Story**: As a QA engineer, I want automated concurrent stress tests to prove that the system prevents double-spending and maintains high throughput.
- **EARS Acceptance Criteria**:
  - `UNDER 50 simultaneous debit threads targeting an account with insufficient balance, THE system SHALL maintain balance >= 0.0000 PHP and achieve >= 200 TPS throughput.`

#### `OPS-803`: SonarQube Code Quality & Security Audit Sweep (5 Points)
- **User Story**: As a tech lead, I want automated static analysis ensuring code quality, test coverage, and security compliance.
- **EARS Acceptance Criteria**:
  - `THE codebase SHALL pass SonarQube quality gate with zero blocker vulnerabilities, zero runtime print statements, and test coverage >= 80%.`
