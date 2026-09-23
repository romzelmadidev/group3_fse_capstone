# Core Retail Ledger & Balance Mutation Engine
> **Track:** Full-Stack Engineering (FSE) Capstone  
> **Theme:** High-Throughput Relational Balance Mutation, Distributed In-Memory Validation, and Data Auditing.

---

## 1. System Overview

The **Core Retail Ledger & Balance Mutation Engine** is an enterprise-grade core banking platform engineered to process high-throughput balance mutations (transfers, deposits, withdrawals, credit draws) with **zero double-spending**, **sub-50ms latency**, and **tamper-proof auditing**.

### The Core Problem Solved:
In concurrent retail banking, if two requests simultaneously attempt to debit ₱50 from an account with a ₱60 balance, both threads could read ₱60 before either commits, resulting in an illegal balance of -₱40 (**Double-Spend Vulnerability**). 

Furthermore, if an account balance updates in the master store but the corresponding audit logging fails mid-flight, the system enters **Data Drift**, leaving un-audited financial state changes.

### Key Architectural Solutions:
1. **Pessimistic Row-Level Locking:** Uses Spring Data JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` executing native `SELECT balance_amount FROM balance_master WHERE account_id = ? FOR UPDATE` inside Oracle XE 21c to force deterministic serialization of updates.
2. **Two-Phase Dual-Write Storage Topology:**
   * **Master State (Oracle XE 21c):** Houses the active balance master record. Updates modify the row state in-place.
   * **Immutable Audit Trail (PostgreSQL 15+):** Strictly append-only historical log in `ledger_mutation_audit`. An automated database trigger blocks any `UPDATE` or `DELETE` statements.
   * **Data Drift Remediation:** If the PostgreSQL audit insert fails or drops offline, Spring Boot triggers a clean rollback on Oracle XE and throws a custom checked `LedgerPersistenceException`.
3. **Transactional Outbox & Kafka Bus:** Asynchronous decoupling via `outbox_events` and Kafka Event Bus (KRaft :9092) ensures non-critical workflows (notifications, external analytics) never degrade synchronous transaction throughput.
4. **Distributed Caching & Token Rotation (Redis :6379):** Lightning-fast idempotency checks ($\le 5\text{ms}$) and token rotation managed by the Account Service and verified at the API Gateway perimeter.
5. **HikariCP Connection Pool Isolation:** Strictly bounded connection pools (`maximum-pool-size=30`, `minimum-idle=5`) to prevent database thread starvation and connection exhaustion under peak load.

---

## 2. Performance Targets & SLAs

| Metric | Target SLA | Meaning |
| :--- | :--- | :--- |
| **Throughput** | $\ge$ 800 TPS | Core engine maintains $\ge 800$ transactions per second during peak concurrency windows. |
| **P95 Latency** | $\le$ 50 ms | 95% of incoming mutation requests compute and respond in under 50 milliseconds. |
| **Cache Turnaround** | $\le$ 5 ms | Redis distributed token and idempotency verification turnaround within 5 milliseconds. |

---

## 3. Repository Structure

```
core-retail-ledger/
├── .gitignore
├── README.md                            # Complete setup & system overview guide
├── infrastructure/
│   ├── docker-compose.yml               # Orchestrates Oracle XE, PostgreSQL 15, and Redis
│   ├── oracle/
│   │   └── init.sql                     # Oracle DDL, NUMBER(18,4) constraints, and seed data
│   └── postgres/
│       └── init.sql                     # PostgreSQL audit DDL, immutability trigger, and seed data
├── frontend/                            # Retail Banking SPA (React 18 + Vite :3000)
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   └── src/                             # Customer, Teller, and Admin views
└── backend/
    ├── pom.xml                          # Root Maven aggregator POM (Java 21, Spring Boot 3.3)
    ├── common-contracts/                # Shared DTOs, Enums, and custom Exceptions
    │   ├── pom.xml
    │   └── src/main/java/com/bank/ledger/contracts/
    │       ├── dto/                     # MutationRequest (JSR-380 @Digits), MutationResponse
    │       ├── enums/                   # MutationType, EventType, AccountType, AccountStatus
    │       └── exception/               # LedgerPersistenceException, InsufficientFundsException
    ├── ledger-mutation-engine/          # Core balance engine (:8082), locking, dual-write
    │   ├── pom.xml
    │   └── src/main/resources/
    │       └── application.properties   # Tuned HikariCP properties (max=30, min=5)
    └── account-service/                 # User onboarding, KYC, and credit appraisals (:8081)
        ├── pom.xml
        └── src/main/resources/
            └── application.properties   # Account service HikariCP properties (max=15, min=5)
```

---

## 4. Quickstart & Onboarding Guide for Team Members

### Prerequisites
* **Docker Desktop** (Running with WSL2 or Hyper-V backend)
* **Java Development Kit (JDK 21+)**
* **Apache Maven 3.9+**

### Step 1: Start Database & Cache Infrastructure
From the repository root directory:
```bash
cd infrastructure
docker compose up -d
```
*This command starts:*
* **Oracle XE 21c** on port `1521` (Self-initializes schema and seed data from `oracle/init.sql`).
* **PostgreSQL 15** on port `5432` (Self-initializes `ledger_mutation_audit` and immutability trigger from `postgres/init.sql`).
* **Redis 7** on port `6379`.

To check container health:
```bash
docker compose ps
```

### Step 2: Build the Backend Multi-Module Project
From the repository root:
```bash
cd backend
mvn clean install
```

### Step 3: Run the Services
* **Account Service:**
  ```bash
  cd backend/account-service
  mvn spring-boot:run
  ```
  *Runs on `http://localhost:8081`*

* **Ledger Mutation Engine:**
  ```bash
  cd backend/ledger-mutation-engine
  mvn spring-boot:run
  ```
  *Runs on `http://localhost:8082`*

---

## 5. Security & Perimeter Data Validation Rules

* **Endpoint:** `POST /api/v1/ledger/mutate`
* **JSR-380 Constraints:**
  * `mutation_amount`: `@NotNull`, `@Positive`, `@Digits(integer=14, fraction=4)`
  * Blocks any negative, non-numeric, or excessively fractioned amounts at the controller perimeter.
* **Problem Details (RFC-7807):**
  * Malformed JSON schemas and validation violations are caught by a global `@ControllerAdvice` and converted into standard RFC-7807 fault structures.
