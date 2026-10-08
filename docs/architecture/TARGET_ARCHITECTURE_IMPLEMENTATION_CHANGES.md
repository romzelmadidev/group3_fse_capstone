# Target Architecture Transition: Implementation Changelog & Technical Reference

**Document Version:** 1.0.0  
**Implementation Date:** October 8, 2026  
**Related Architecture Specification:** [`TARGET_ARCHITECTURE_TRANSITION_DESIGN.md`](./TARGET_ARCHITECTURE_TRANSITION_DESIGN.md)  
**Target Environment:** Microservices on Java 21 LTS / Spring Boot 3.3.5 / Microsoft Azurite Blob Storage / Apache Kafka / Redis / Oracle & Azure SQL / PostgreSQL Audit Vault

---

## 1. Executive Summary

This document provides a comprehensive technical changelog of all components, database schemas, microservice codebases, and infrastructure configurations introduced to realize the event-driven target banking architecture outlined in `TARGET_ARCHITECTURE_TRANSITION_DESIGN.md`.

### Core Architectural Shift
* **Decoupled Custodianship**: Split peripheral transaction orchestration from core ledger mutations. High-throughput ingestion, anti-scam cooling periods, biometric step-ups, and AI risk checks are moved to a stateless perimeter layer ([`transfer-orchestrator`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator)).
* **Single Source of Truth**: The Core Banking System ([`t24-mock-cbs`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs)) retains exclusive custodianship over the primary relational database and the immutable PostgreSQL audit vault, enforcing sub-5ms row locks, double-entry GL balance validation, transactional outbox streaming, Maker-Checker reversal governance, and a 5-phase Close-of-Business (COB) lifecycle.
* **Stateless Compliance & Microsoft Azurite Storage**: Regulatory filings, customer e-statements, GL trial balance spreadsheets, BIR tax certificates, and AMLA Covered Transaction Reports (CTR) are offloaded to [`compliance-service`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service). All binary and XML artifacts are persisted to the official **Microsoft Azurite Docker container** (`mcr.microsoft.com/azure-storage/azurite`), paired with SHA-256 cryptographic integrity validation and automated metadata synchronization to the CBS audit tables.

```
                      +-----------------------------+
                      |   Spring Cloud Gateway      |
                      |        (:8080)              |
                      +--------------+--------------+
                                     |
               +---------------------+---------------------+
               |                                           |
               v                                           v
+-------------------------------+             +-----------------------------+
|    transfer-orchestrator      |             |     compliance-service      |
|    (Perimeter, Zero-DB)       |             |   (Regulatory, Zero-DB)     |
|          (:8082)              |             |          (:8086)            |
+---------------+---------------+             +--------------+--------------+
                |                                            |
         Temenos OFS (HTTP)                           Azure Blob SDK
                |                                            |
                v                                            v
+-------------------------------+             +-----------------------------+
|        t24-mock-cbs           |             |    Microsoft Azurite Blob   |
|   (Master DB & Audit Custodian|             |          Container          |
|          (:8085)              |             |        (:10000-10002)       |
+-------+---------------+-------+             +-----------------------------+
        |               |
        v               v
  [Primary DB]    [Postgres Audit Vault]
  (Oracle / SQL)  (Immutable Chained Audit)
```

---

## 2. Infrastructure & Environment Modifications

### 2.1 Microsoft Azurite Docker Container Integration
* **File:** [`infrastructure/docker-compose.yml`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/docker-compose.yml)
* **Changes:**
  * Added the official `mcr.microsoft.com/azure-storage/azurite:latest` service named `azurite`.
  * Port Bindings:
    * `10000:10000` (Azure Blob Service)
    * `10001:10001` (Azure Queue Service)
    * `10002:10002` (Azure Table Service)
  * Mounted persistent volume `azurite_data:/data` with command `--blobHost 0.0.0.0 --queueHost 0.0.0.0 --tableHost 0.0.0.0 --location /data --debug /data/debug.log`.
  * Configured standard local development connection string:
    ```
    DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://localhost:10000/devstoreaccount1;
    ```

### 2.2 Port Conflict Resolution
* **File:** [`infrastructure/docker-compose.yml`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/docker-compose.yml)
* **Changes:**
  * Re-mapped `kafka-ui` external port from `8085:8080` to `8089:8080`.
  * Reserved host port `8085` exclusively for the new `t24-mock-cbs` core banking service.

### 2.3 Container Definitions Added
* **Added services to Docker Compose:**
  * `t24-mock-cbs` (Port `8085`): Depend on `primary-db`, `postgres-audit`, and `kafka`.
  * `transfer-orchestrator` (Port `8082`): Depend on `redis`, `kafka`, `risk-service`, and `t24-mock-cbs`.
  * `compliance-service` (Port `8086`): Depend on `azurite`, `kafka`, and `t24-mock-cbs`.

---

## 3. Database Schema & DDL Updates

### 3.1 Primary Master DB Schemas
* **Files Modified:**
  * Oracle: [`infrastructure/oracle/01_create_tables_and_constraints.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/oracle/01_create_tables_and_constraints.sql)
  * Azure SQL / SQL Server: [`infrastructure/azure/01_azure_sql_master_schema.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/azure/01_azure_sql_master_schema.sql)
* **Tables Defined & Aligned:**
  1. `accounts_master`: Primary customer account registry with status (`ACTIVE`, `BLOCKED`, `FROZEN`, `DORMANT`).
  2. `balances_master`: Balance snapshot maintaining `balance_amount`, `hold_amount`, `available_balance`, `currency`.
  3. `transactions_master`: ACID transaction log including OFS transaction reference and `status`.
  4. `transaction_status_history`: Historical status transition breadcrumbs.
  5. `gl_chart_of_accounts`: Chart of accounts categorized by `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE`.
  6. `gl_balances`: Running GL category balances.
  7. `gl_ledger`: Double-entry balanced journal entries enforcing matching Debits and Credits.
  8. `reversal_requests`: Maker-Checker workflow tracking disputes, approval states, and reviewer audit trails.
  9. `system_dates`: Core banking business date tracker with `posting_window_open` and operational status (`ONLINE`, `EOD_CUTOFF`, `EOD_PROCESSING`, `ERROR_HALTED`).
  10. `cob_batch_logs`: 5-phase COB batch execution logs.
  11. `uncollected_fees`: Uncollected below-min ADB fee ledger for zero-overdraft protection.
  12. `interest_accruals`: Daily interest accrued and 20% tax withholding calculations.
  13. `eod_balance_snapshots`: Immutable historical balance snapshots frozen during Phase 3.
  14. `outbox_events`: Transactional outbox event store for at-least-once Kafka publication.

### 3.2 Immutable PostgreSQL Audit Vault Schemas
* **Files Modified:**
  * Local Docker PostgreSQL: [`infrastructure/postgres/init.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/postgres/init.sql)
  * Azure PostgreSQL: [`infrastructure/azure/02_azure_postgres_audit_schema.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/azure/02_azure_postgres_audit_schema.sql)
* **Tables Defined & Aligned:**
  1. `ledger_mutation_audit`: Cryptographically chained (`prev_hash`, `sha256_hash`) record of every debit and credit.
  2. `reversal_audit`: Chained audit trail of Maker file requests and Checker approve/reject verdicts.
  3. `transaction_status_audit`: Chained log of all status transitions (`PENDING` -> `SETTLED`, `REVERSED`, etc.).
  4. `failed_transactions_audit`: Dead-Letter Queue (DLQ) diagnostic repository capturing error codes and raw payloads.
  5. `eod_reports_metadata`: Registry of generated compliance reports, SHA-256 hashes, and Azurite blob URLs.
  6. `compliance_filings`: AMLA and BIR regulatory filing registry.

---

## 4. Shared Contracts & Protocols (`common-contracts`)

* **Maven Module:** [`backend/common-contracts`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts)
* **Artifact:** `com.bank.ledger:common-contracts:1.0.0-SNAPSHOT`

### 4.1 Enums Added
* [`TransactionStatus.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/enums/TransactionStatus.java): Standard lifecycle statuses (`PENDING`, `COOLING_OFF`, `RISK_REJECTED`, `SETTLED`, `REVERSED`, `FAILED_DLQ`, etc.).
* [`ChangeReasonCode.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/enums/ChangeReasonCode.java): Audit reasons (`ACID_LEDGER_COMMITTED`, `MAKER_DISPUTE_FILED`, `CHECKER_REVERSAL_APPROVED_SETTLED`, `CHECKER_REVERSAL_REJECTED`, `DLQ_EXHAUSTED`).
* [`ActorType.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/enums/ActorType.java): Actor classifications (`CUSTOMER`, `SYSTEM_ORCH`, `SYSTEM_CBS`, `TELLER_MAKER`, `MANAGER_CHECKER`).

### 4.2 Event DTOs (Kafka Payloads)
* [`TransferExecutedEvent.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/events/TransferExecutedEvent.java): Published upon successful fund settlement.
* [`TransferReversedEvent.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/events/TransferReversedEvent.java): Published when a Maker-Checker reversal is approved.
* [`TransactionStatusChangedEvent.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/events/TransactionStatusChangedEvent.java): Emitted on every state transition.
* [`TransferFailedToDlqEvent.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/events/TransferFailedToDlqEvent.java): Published to `banking.transfers.dlq` when perimeter retry budgets expire.
* [`BalanceSnapshotFrozenEvent.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/events/BalanceSnapshotFrozenEvent.java): Emitted during COB Phase 3.
* [`EodCompletedEvent.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/events/EodCompletedEvent.java): Signals successful completion of all 5 COB phases.

### 4.3 Temenos Open Financial Services (OFS) Syntax
* [`OfsMessageUtil.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/ofs/OfsMessageUtil.java): Serializer and parser for standard Temenos OFS syntax:
  * `FUNDS.TRANSFER,INITIATE/I/PROCESS//0,USER//PASSWORD,TRANSACTION.ID:1:1=...,DEBIT.ACCT.NO:1:1=...,...`
  * `FUNDS.TRANSFER,REVERSAL/I/PROCESS//0,USER//PASSWORD,REVERSAL.ID:1:1=...,ORIGINAL.TXN.ID:1:1=...,...`

---

## 5. New Microservice 1: Core Banking System (`t24-mock-cbs`)

* **Directory:** [`backend/t24-mock-cbs`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs)
* **Port:** `8085`
* **Responsibilities:** Primary Master DB and Immutable Audit DB custodian.

### 5.1 Architecture & Configurations
* **Dual-DataSource Architecture**:
  * [`PrimaryDataSourceConfig.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/config/PrimaryDataSourceConfig.java): Manages connections to Oracle / Azure SQL (`primaryEntityManagerFactory`, `primaryTransactionManager`).
  * [`AuditDataSourceConfig.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/config/AuditDataSourceConfig.java): Manages connections to PostgreSQL Audit Vault (`auditEntityManagerFactory`, `auditTransactionManager`).
* **Entity Layers**:
  * 14 JPA Master entities mapped under package `com.bank.cbs.entity.master` with 14 corresponding repositories.
  * 6 JPA Audit entities mapped under package `com.bank.cbs.entity.audit` with 6 corresponding repositories.

### 5.2 Core Business Services
* [`CbsFundsTransferService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsFundsTransferService.java):
  * **Ascending Row Locks**: Evaluates `sourceAccountId.compareTo(targetAccountId)` and locks accounts in strict lexicographical order (`p_src < p_dst ? lock_src_then_dst : lock_dst_then_src`) to guarantee zero database deadlocks under high concurrency.
  * **Solvency Checks**: Confirms `availableBalance >= amount`.
  * **Posting Window Check**: Validates `posting_window_open == true` in `system_dates`.
  * **Double-Entry GL Balancing**: Synchronously posts debit to Asset/Liability account and balancing credit to target ledger.
  * **Transactional Outbox**: Writes `TransferExecutedEvent` and `TransactionStatusChangedEvent` to `outbox_events` in the same database commit.
* [`CbsReversalService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsReversalService.java):
  * Two-man rule (Maker-Checker): Maker files reversal request (`PENDING_APPROVAL`); Checker executes approval or rejection.
  * Enforces segregation of duties: `checkerUserId != makerUserId`.
  * Compensating GL journal entries posted upon approval (`REVERSED` status).
* [`CbsCobBatchService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsCobBatchService.java):
  * Implements the 5-phase COB state machine:
    * *Phase 0*: Window Cutoff (`EOD_CUTOFF`).
    * *Phase 1*: Below-Min ADB Fee deduction (₱300 fee evaluated; if balance < ₱300, deducts remaining balance without overdrawing, logging remaining to `uncollected_fees`).
    * *Phase 2*: Daily Interest Accrual (0.5% p.a. divided by 365, with 20% withholding tax).
    * *Phase 3*: Balance Snapshot Freezing and GL Level 1 Reconciliation ($\sum \text{Debits} == \sum \text{Credits}$). If unbalanced, transitions to `ERROR_HALTED` tripwire.
    * *Phase 4*: Business Date Rollover to $T+1$ and re-opening of the posting window (`ONLINE`).
* [`CbsAuditSelfConsumptionService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsAuditSelfConsumptionService.java):
  * Asynchronously consumes events from `banking.transfers.events`.
  * Populates `ledger_mutation_audit`, `reversal_audit`, and `transaction_status_audit`.
  * Computes SHA-256 cryptographic chaining: `sha256(prevHash + recordPayload)`.
* [`CbsAuditQueryService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsAuditQueryService.java):
  * Provides query capabilities over audit tables and registers regulatory report metadata.

### 5.3 REST Controllers
* [`CbsPostingController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsPostingController.java): Accepts JSON and raw Temenos OFS transfer requests (`POST /api/v1/cbs/postings`, `POST /api/v1/cbs/ofs`).
* [`CbsReversalController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsReversalController.java): Maker-Checker reversal operations (`POST /api/v1/cbs/reversals/file`, `POST /api/v1/cbs/reversals/evaluate`).
* [`CobController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CobController.java): Manual trigger and status inspection for COB batch (`POST /api/v1/cbs/cob/run`, `GET /api/v1/cbs/cob/status`).
* [`CbsAuditController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsAuditController.java): Exposes mutation audit, reversal audit, DLQ failures, and report metadata.

---

## 6. New Microservice 2: Perimeter Transfer Orchestrator (`transfer-orchestrator`)

* **Directory:** [`backend/transfer-orchestrator`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator)
* **Port:** `8082`
* **Responsibilities:** Zero-DB stateless perimeter security, idempotency, cooling-off locks, biometric assertion, pre-flight AI risk evaluation, and upstream circuit breaking.

### 6.1 Perimeter Defense Guards (Redis-backed)
* [`IdempotencyLockService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/IdempotencyLockService.java):
  * Key format: `tx:idempotency:<idempotencyKey>`.
  * TTL: 60 seconds (`SETNX`). Blocks duplicate rapid requests.
* [`CoolOffService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/CoolOffService.java):
  * Key format: `tx:cooloff:<transactionId>`.
  * TTL: 600 seconds (10 minutes). Enforces anti-scam hold on first-time or unverified beneficiaries.
* [`BiometricChallengeService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/BiometricChallengeService.java):
  * Key format: `tx:biometric:<transactionId>`.
  * Manages cryptographic device challenges. Limits failed verification attempts to 3 before permanently invalidating the challenge.

### 6.2 Pre-Flight Risk Integration
* [`RiskClientService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/RiskClientService.java):
  * Calls `risk-service:8084` via Spring WebClient (`POST /api/v1/risk/evaluate`).
  * If risk score $\ge 85$ or recommendation is `BLOCK`, immediate 403 Forbidden with `FRAUD_BLOCKED`.
  * If risk score indicates step-up authentication is needed, issues biometric challenge.

### 6.3 Upstream Resilience & DLQ Routing
* [`CbsClientService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/CbsClientService.java):
  * Serializes payloads into Temenos OFS syntax via `OfsMessageUtil`.
  * Calls `t24-mock-cbs:8085` under a **Resilience4j Circuit Breaker** (`cbsServiceCircuitBreaker`).
  * On upstream timeout or circuit trip, enqueues `TransferFailedToDlqEvent` to Kafka topic `banking.transfers.dlq`.

### 6.4 Perimeter Controllers
* [`TransferOrchestratorController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/TransferOrchestratorController.java): Primary ingestion endpoint (`POST /api/v1/transfers/initiate`, `POST /api/v1/transfers/biometric-verify`).
* [`ReversalOrchestratorController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/ReversalOrchestratorController.java): Perimeter gateway for Maker dispute filing and Checker approval.

---

## 7. New Microservice 3: Regulatory Compliance Engine (`compliance-service`)

* **Directory:** [`backend/compliance-service`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service)
* **Port:** `8086`
* **Responsibilities:** Zero-DB regulatory document generation, Microsoft Azurite Blob Storage persistence, DLQ inspection, and manual replay.

### 7.1 Microsoft Azurite Storage Integration
* **Library:** `com.azure:azure-storage-blob:1.26.2`
* [`AzuriteBlobStorageConfig.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/config/AzuriteBlobStorageConfig.java): Configures `BlobServiceClient` with local Azurite connection string.
* [`AzuriteBlobStorageService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/service/AzuriteBlobStorageService.java):
  * Automatically creates and initializes the `compliance-vault` blob container upon service startup.
  * Streams artifact bytes directly to Azurite (`uploadBlob`).
  * Downloads blob bytes for client streaming (`downloadBlob`).
  * Computes SHA-256 cryptographic checksums for every uploaded artifact.

### 7.2 Regulatory Document Generators
* [`CustomerStatementPdfGenerator.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/generator/CustomerStatementPdfGenerator.java):
  * Built using **OpenPDF 3** (`org.openpdf.text.*`).
  * Generates branded PDF account statements with opening balance, transaction ledger, and closing balance.
* [`GlTrialBalanceGenerator.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/generator/GlTrialBalanceGenerator.java):
  * Built using **Apache POI 5.5.1** (Excel `.xlsx`) and **OpenPDF 3** (PDF `.pdf`).
  * Compiles GL Level 1 trial balances with Total Debits, Total Credits, and variance checks.
* [`BirTaxCertificateGenerator.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/generator/BirTaxCertificateGenerator.java):
  * Generates formal Bureau of Internal Revenue (BIR) Form 2306 withholding tax certificates for 20% final withholding tax on interest earned.
* [`AmlaCtrGenerator.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/generator/AmlaCtrGenerator.java):
  * Automatically triggers for any single cash/fund transfer $\ge \text{PHP } 500,000.00$.
  * Generates an Anti-Money Laundering Council (AMLC) Covered Transaction Report (CTR) formatted XML document.

### 7.3 Asynchronous Kafka Event Consumer
* [`ComplianceKafkaConsumer.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/consumer/ComplianceKafkaConsumer.java):
  * Listens on `banking.transfers.events` and `banking.batch.events`.
  * Detects CTR-eligible transactions and automatically uploads XML packages to Azurite.
  * Detects `EodCompletedEvent` and automatically triggers GL Trial Balance generation.
  * Registers generated report metadata with `t24-mock-cbs:8085` (`POST /api/v1/cbs/audit/report-metadata`).

### 7.4 DLQ Inspection & Replay Engine
* [`ComplianceController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/controller/ComplianceController.java):
  * `GET /api/v1/compliance/dlq/incidents`: Queries DLQ incident records from CBS audit tables.
  * `POST /api/v1/compliance/dlq/replay/{transactionId}`: Fetches failed payload and resubmits to `transfer-orchestrator` with header `X-Manual-Replay: true`.
  * `GET /api/v1/compliance/storage/download/{blobName}`: Directly streams binary or XML documents stored in Azurite to authenticated consumers.

---

## 8. API Gateway Routing Updates (`gateway-service`)

* **File:** [`backend/gateway-service/src/main/resources/application.yml`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/gateway-service/src/main/resources/application.yml)
* **Changes:**
  * Updated Spring Cloud Gateway routes:
    * `/api/v1/transfers/**` $\rightarrow$ `http://localhost:8082` (`transfer-orchestrator`)
    * `/api/v1/reversals/**` $\rightarrow$ `http://localhost:8082` (`transfer-orchestrator`)
    * `/api/v1/compliance/**` $\rightarrow$ `http://localhost:8086` (`compliance-service`)
    * `/api/v1/cbs/**` and `/api/v1/t24/**` $\rightarrow$ `http://localhost:8085` (`t24-mock-cbs`)

---

## 9. Comprehensive File & Class Directory

| File Path | Component | Description |
|:---|:---|:---|
| [`infrastructure/docker-compose.yml`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/docker-compose.yml) | Infra | Added Azurite container, remapped kafka-ui to 8089, wired new services |
| [`infrastructure/azure/01_azure_sql_master_schema.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/azure/01_azure_sql_master_schema.sql) | DDL | Azure SQL master tables with constraints & indexes |
| [`infrastructure/azure/02_azure_postgres_audit_schema.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/azure/02_azure_postgres_audit_schema.sql) | DDL | Azure PostgreSQL audit schema with hash-chaining |
| [`infrastructure/oracle/01_create_tables_and_constraints.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/oracle/01_create_tables_and_constraints.sql) | DDL | Oracle master DB DDL |
| [`infrastructure/postgres/init.sql`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/infrastructure/postgres/init.sql) | DDL | Local PostgreSQL audit vault DDL |
| [`backend/gateway-service/src/main/resources/application.yml`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/gateway-service/src/main/resources/application.yml) | Gateway | Route mapping to ports 8082, 8085, 8086 |
| [`backend/common-contracts/.../enums/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/enums) | Contracts | `TransactionStatus`, `ChangeReasonCode`, `ActorType` |
| [`backend/common-contracts/.../events/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/events) | Contracts | Kafka domain events |
| [`backend/common-contracts/.../ofs/OfsMessageUtil.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/common-contracts/src/main/java/com/bank/ledger/contracts/ofs/OfsMessageUtil.java) | Contracts | Temenos OFS syntax generator and parser |
| [`backend/t24-mock-cbs/src/main/java/com/bank/cbs/config/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/config) | CBS (:8085) | `PrimaryDataSourceConfig`, `AuditDataSourceConfig` |
| [`backend/t24-mock-cbs/src/main/java/com/bank/cbs/entity/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/entity) | CBS (:8085) | 14 Master entities + 6 Audit entities |
| [`backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service) | CBS (:8085) | `CbsFundsTransferService`, `CbsReversalService`, `CbsCobBatchService`, `CbsAuditSelfConsumptionService`, etc. |
| [`backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller) | CBS (:8085) | `CbsPostingController`, `CbsReversalController`, `CobController`, `CbsAuditController` |
| [`backend/t24-mock-cbs/src/test/.../CbsCoreBankingServiceTest.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/test/java/com/bank/cbs/CbsCoreBankingServiceTest.java) | CBS (:8085) | Unit test suite (Solvency, Ascending Lock, GL, Maker-Checker, COB) |
| [`backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service) | Orch (:8082) | `IdempotencyLockService`, `CoolOffService`, `BiometricChallengeService`, `RiskClientService`, `CbsClientService` |
| [`backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller) | Orch (:8082) | `TransferOrchestratorController`, `ReversalOrchestratorController` |
| [`backend/transfer-orchestrator/src/test/.../TransferOrchestrationServiceTest.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/test/java/com/bank/orchestrator/TransferOrchestrationServiceTest.java) | Orch (:8082) | Unit test suite (Idempotency, Fraud block, Biometrics, Cool-off) |
| [`backend/compliance-service/src/main/java/com/bank/compliance/service/AzuriteBlobStorageService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/service/AzuriteBlobStorageService.java) | Comp (:8086) | Azure Blob SDK integration with Microsoft Azurite container |
| [`backend/compliance-service/src/main/java/com/bank/compliance/generator/*`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/generator) | Comp (:8086) | `CustomerStatementPdfGenerator`, `GlTrialBalanceGenerator`, `BirTaxCertificateGenerator`, `AmlaCtrGenerator` |
| [`backend/compliance-service/src/main/java/com/bank/compliance/consumer/ComplianceKafkaConsumer.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/consumer/ComplianceKafkaConsumer.java) | Comp (:8086) | Kafka event listener for automated artifact persistence |
| [`backend/compliance-service/src/main/java/com/bank/compliance/controller/ComplianceController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/controller/ComplianceController.java) | Comp (:8086) | Document download, statement generation, DLQ incident & replay |
| [`backend/compliance-service/src/test/.../ComplianceServiceTest.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/test/java/com/bank/compliance/ComplianceServiceTest.java) | Comp (:8086) | Unit test suite (Statement PDF, Excel GL, Tax PDF, AMLA CTR XML, Azurite mock) |

---

## 10. Verification & Test Evidence

### 10.1 Automated Unit & Integration Tests
Full Maven test run across all modules completed with 100% pass rate:

```shell
mvn test -pl common-contracts,t24-mock-cbs,transfer-orchestrator,compliance-service
```

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.bank.cbs.CbsCoreBankingServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.112 s
...
[INFO] Running com.bank.orchestrator.TransferOrchestrationServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.220 s
...
[INFO] Running com.bank.compliance.ComplianceServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.834 s
...
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for Common Contracts & DTOs 1.0.0-SNAPSHOT:
[INFO] 
[INFO] Common Contracts & DTOs ............................ SUCCESS [  0.949 s]
[INFO] T24 Mock Core Banking System ....................... SUCCESS [  9.012 s]
[INFO] Transfer Orchestrator .............................. SUCCESS [  4.464 s]
[INFO] Compliance & Reporting Service ..................... SUCCESS [  3.045 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time:  17.918 s
```

### 10.2 Standalone Executable Spring Boot JARs
All microservices were successfully packaged:

```shell
mvn package -DskipTests -pl t24-mock-cbs,transfer-orchestrator,compliance-service
```

```text
[INFO] Building jar: .../backend/t24-mock-cbs/target/t24-mock-cbs-1.0.0-SNAPSHOT.jar
[INFO] Building jar: .../backend/transfer-orchestrator/target/transfer-orchestrator-1.0.0-SNAPSHOT.jar
[INFO] Building jar: .../backend/compliance-service/target/compliance-service-1.0.0-SNAPSHOT.jar
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

---

## 11. Core Banking Remediation Actions (Security, Reliability & Compliance)

### 11.1 Action 1: Sanitized Client-Facing Compliance Error Responses
* **Gap Addressed**: Direct leaking of AMLA suspicious indicators or internal investigation docket references in client error payloads.
* **Implementation**: Transformed client error responses across Gateway, Account Service, and Transfer Orchestrator into standardized, generic denial codes (`SEC-ERR-001` / `403 Forbidden`). Internal compliance telemetry is exclusively routed to internal logging and Kafka audit topics.

### 11.2 Action 2: Database-Level Idempotency Key Constraint
* **Gap Addressed**: Network timeouts or replayed OFS commands mutating account balances multiple times.
* **Implementation**: Added `idempotency_key VARCHAR(128) UNIQUE` to `transactions` table. Enhanced `CbsFundsTransferService` to intercept duplicate idempotency keys, replaying original transaction responses without re-executing balance mutations or GL postings.

### 11.3 Action 3: Authoritative Core Banking Fund Holds
* **Gap Addressed**: Reliance on ephemeral Redis distributed locks during multi-minute cooling-off periods allowing double-spending if Redis restarted.
* **Implementation**: Integrated authoritative fund holds directly in `balance_master.hold_amount`. Introduced `CbsHoldService`, `CbsHoldController` (`POST /api/v1/cbs/holds`, `POST /api/v1/cbs/holds/release`), and integrated `TransferOrchestrationService` to place core banking holds upon entering the 10-minute anti-scam cooling-off window.

### 11.4 Action 4.B: Cryptographic Dynamic Linking for Biometric Challenges
* **Gap Addressed**: Replay or man-in-the-middle manipulation of static biometric challenge nonces (BSP Circular 1140 & PSD2 RTS Art. 5).
* **Implementation**: Built `BiometricChallengeService` computing SHA-256 canonical hash bindings: `SHA256(txId + "|" + targetAccountId + "|" + amount + "|" + currency + "|" + nonce)`. Any alteration of the beneficiary or amount invalidates the biometric signature assertion.

### 11.5 Action 5: Cleared ADB Calculations, Leap-Year Conventions & Arrears Sweeping
* **Gap Addressed**: Interest accrual on uncollected/held balances, hardcoded 365-day year division failing during leap years, and dormant uncollected below-min ADB fees.
* **Implementation**: Updated `CbsCobBatchService`:
  * Calculated daily interest accrual strictly against cleared balances (`balanceAmount - holdAmount`).
  * Dynamically resolved days in year via `cobDate.lengthOfYear()` (366 in leap years, 365 otherwise).
  * Implemented automated arrears sweeping in Phase 1 to automatically recover outstanding below-minimum balance fees from `uncollected_fee_master` as soon as cleared funds are credited.

### 11.6 Action 6 (Option B): High-Throughput Merkle-Tree Block Anchoring
* **Gap Addressed**: Serialized single-thread database write bottleneck caused by global sequential SHA-256 hash chaining ($H_N = \text{SHA256}(H_{N-1} \parallel Tx_N)$).
* **Implementation**:
  * **Parallel Record Ingestion**: Audit workers write `LedgerMutationAudit` records concurrently into PostgreSQL without locking or waiting on preceding row hashes. Each record computes its own intrinsic leaf cryptographic hash `sha256Hash`.
  * **MerkleTreeService**: Asynchronously constructs balanced Merkle Trees from batched mutation hashes, computing a single tamper-evident `Merkle_Root`.
  * **AuditBlockAnchor**: Periodically anchors batches of audit records into `audit_block_anchor` table, chaining block roots sequentially ($\text{BlockHash}_K = \text{SHA256}(\text{MerkleRoot}_K \parallel \text{PrevBlockHash}_{K-1} \parallel K)$).
  * **Logarithmic $O(\log N)$ Proof Verification**: Provided `getMerkleProof` and `verifyMerkleProof` APIs enabling instant mathematical verification of transaction inclusion without full-table scans.
