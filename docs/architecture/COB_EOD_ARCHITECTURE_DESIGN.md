# High-Level Design Architecture: Close of Business (COB) & End-of-Day (EOD)

## 1. Executive Architectural Distinction: COB vs. EOD

In modern core banking architectures (specifically modeling Temenos T24 / Transact), **COB** and **EOD** represent two distinct architectural tiers of the daily financial closing cycle:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│               CLOSE OF BUSINESS (COB) — MASTER OPERATIONAL STATE MACHINE               │
│                                                                                        │
│  [Phase 0: Pre-COB]      [Phases 1-3: Core EOD Batch]     [Phase 4: Post-COB / SOB]    │
│  • Channel Drain         ┌──────────────────────────────┐ • Business Date Rollover     │
│  • Cutoff Enforcement    │ End-of-Day (EOD) Accounting: │   (T ➔ T+1)                  │
│  • T+1 Value-Date Tag    │ • Subfeature 4.2: Fees       │ • Reset Daily Limits         │
│  • In-Flight Clearing    │ • Subfeature 4.3: Interest   │ • Re-open Online Processing  │
│                          │ • Subfeature 4.1: Reports/GL │ • Unbuffer T+1 Queue         │
│                          └──────────────────────────────┘                              │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.1 Comparison Matrix

| Architectural Dimension | **End-of-Day (EOD)** | **Close of Business (COB)** |
| :--- | :--- | :--- |
| **Domain Scope** | **Financial Accounting & Ledger Computation** | **System Lifecycle & Operational State Machine** |
| **Question It Answers** | *"What financial balances, fees, and interest must be calculated for Date $T$?"* | *"How does the core banking platform transition operationally from Date $T \to T+1$?"* |
| **Hierarchy** | **Child Module / Computational Engine** (runs *inside* COB Phases 1–3). | **Parent Container / Master Orchestrator** (coordinates Phases 0 through 4). |
| **Key Responsibilities** | • Calculate below-min ADB maintenance fees.<br>• Accrue daily deposit interest and 20% BIR withholding tax.<br>• Freeze closing balance snapshots (`eod_balance_snapshots`).<br>• Verify double-entry GL balance ($\sum \text{Debits} = \sum \text{Credits}$). | • Manage system state transitions (`ONLINE` ➔ `EOD_CUTOFF` ➔ `COB_PROCESSING` ➔ `ROLLOVER` ➔ `ONLINE`).<br>• Drain in-flight channel transfers and tag 24/7 intake to $T+1$.<br>• Sequence and execute the EOD accounting batch.<br>• Advance authoritative calendar date in `system_dates`.<br>• Reset daily customer velocity/transfer limits.<br>• Trigger Start-of-Business (SOB) re-opening. |
| **Calendar Impact** | **NEVER changes the calendar date.** Balances and logs remain strictly for Date $T$. | **Advances the calendar date from $T \to T+1$** in `system_dates`. |
| **Execution Nature** | Purely computational, idempotent, domain-level accounting. | Platform-level orchestration and state lifecycle governance. |

### 1.2 Architectural Domain Ownership (What Belongs to COB vs. EOD)

To make it immediately obvious which components, phases, and entities belong to which domain:

#### A. Belongs to COB (Close of Business) — Platform Operations & Lifecycle
* **Phases**:
  * **Phase 0 (Pre-COB)**: Locking the posting window, draining in-flight transactions, tagging new 24/7 transfers to Date $T+1$.
  * **Phase 4 (Post-COB / Rollover)**: Advancing `system_dates` ($T \to T+1$), resetting daily customer velocity limits, and reopening the system (`ONLINE`).
* **State Machine**: Governs `ONLINE` ➔ `EOD_CUTOFF` ➔ `COB_PROCESSING` ➔ `ROLLOVER` ➔ `ONLINE`, and the `ERROR_HALTED` tripwire.
* **APIs**:
  * `POST /api/v1/cbs/cob/trigger` (Master pipeline trigger)
  * `GET /api/v1/cbs/cob/status` (Active progress monitoring)
  * `GET /api/v1/cbs/system-date` (Authoritative core date/window query)
* **Database Tables**:
  * `system_dates` (Calendar date and platform status)
  * `cob_batch_log` (Macro batch execution audit)
* **Perimeter Role**: `transfer-orchestrator` intercepting daytime transfers during cutoff with `HTTP 202 Accepted { status: "QUEUED_FOR_T_PLUS_1" }`.

#### B. Belongs to EOD (End of Day) — Financial Accounting Engine
* **Phases**:
  * **Phase 1 (Fees)**: Calculating below-minimum ADB fees with Zero-Overdraft Protection.
  * **Phase 2 (Interest & Tax)**: Daily deposit interest accrual and month-end capitalization with 20% BIR withholding tax split.
  * **Phase 3 (Snapshots & GL)**: Freezing daily balance snapshots and double-entry General Ledger balancing ($\sum \text{Debits} = \sum \text{Credits}$).
* **APIs**:
  * `POST /api/v1/cbs/eod/trigger` (Modular accounting calculations without rolling the system calendar).
* **Database Tables**:
  * `balance_master` (Balance mutations for fees & interest)
  * `gl_ledger` (Double-entry journal records)
  * `uncollected_fees` (Arrears tracking for insolvent accounts)
  * `eod_balance_snapshots` (Permanent daily snapshot history)
* **Reporting (Async)**: `compliance-service` generating PDF E-Statements, GL Trial Balance sheets, BIR Form 2306 tax certificates, and AMLA CTR filings.

---

## 2. Component Topology Architecture

```mermaid
flowchart TB
    subgraph Triggers ["Operational Ingress & Triggers"]
        Cron["Automated Batch Scheduler<br/>(Docker Cron / 00:00 UTC)"]
        OpsUI["Operations Admin Portal<br/>(Bank Manager / Ops UI)"]
        ClientApp["Retail Customer App<br/>(Mobile / Web 24/7)"]
    end

    subgraph Perimeter ["Perimeter Tier"]
        GW["gateway-service (:8080)"]
        Orch["transfer-orchestrator (:8082)<br/>• Value-Date T+1 Tagger<br/>• Cutoff Buffer Interceptor"]
    end

    subgraph CoreCBS ["Core Banking Tier: t24-mock-cbs (:8085)"]
        COBController["COB Lifecycle Controller<br/>• POST /api/v1/cbs/cob/trigger<br/>• GET /api/v1/cbs/cob/status"]
        EODEngine["EOD Financial Engine<br/>• POST /api/v1/cbs/eod/trigger<br/>• Phase 1: Fees<br/>• Phase 2: Interest & Tax<br/>• Phase 3: Snapshots & GL"]
        DateSvc["System Calendar Service<br/>• GET /api/v1/cbs/system-date"]
    end

    subgraph DataTier ["Authoritative Master Data Tier (:1433)"]
        MasterDB[("Primary Master DB<br/>• system_dates<br/>• balance_master<br/>• gl_ledger<br/>• uncollected_fees<br/>• eod_balance_snapshots<br/>• cob_batch_log")]
    end

    subgraph EventStream ["Asynchronous Event Streaming (:9092)"]
        Kafka{{"Kafka Broker<br/>banking.transfers.events"}}
    end

    subgraph ComplianceTier ["Compliance & Reporting Tier (:8086)"]
        CompSvc["compliance-service<br/>• PDF E-Statements<br/>• GL Trial Balance<br/>• BIR 2306 Certificates<br/>• AMLA CTR Filings"]
        AuditDB[("Postgres Audit Vault (:5432)<br/>• eod_reports_metadata<br/>• immutable audit chains")]
    end

    %% Trigger connections
    Cron -->|"POST /cob/trigger (Nightly Auto)"| COBController
    OpsUI -->|"POST /cob/trigger (Manual Override)"| COBController
    OpsUI -->|"POST /eod/trigger (Retry/Audit)"| EODEngine
    OpsUI -->|"GET /cob/status (Polling)"| COBController
    ClientApp -->|"POST /transfers (24/7)"| GW
    GW --> Orch

    %% Orchestrator connections
    Orch -->|"GET /cbs/system-date"| DateSvc
    Orch -->|"POST /cbs/transfers (OFS wire)"| CoreCBS

    %% Core CBS Internal Delegations
    COBController -->|"Invokes Phases 1-3"| EODEngine
    COBController -->|"Updates status & date"| MasterDB
    EODEngine -->|"ACID Balances & Snapshots"| MasterDB
    CoreCBS -->|"Emits Batch Events"| Kafka

    %% Asynchronous Reporting
    Kafka -->|"Consumes Snapshots/EodCompleted"| CompSvc
    CompSvc -->|"Writes Hash-Chained Metadata"| AuditDB
```

---

## 3. Master Operational State Machine

The authoritative state of the core banking platform is persisted in `system_dates.status`. All services coordinate around these lifecycle states:

```mermaid
stateDiagram-v2
    [*] --> ONLINE: Initial Platform Boot / SOB Open

    ONLINE --> EOD_CUTOFF: COB Triggered (00:00 UTC)<br/>POST /api/v1/cbs/cob/trigger
    note right of EOD_CUTOFF
        Phase 0: Pre-COB Cutoff
        • In-flight transfers drained
        • New daytime transfers tagged for Date T+1
        • Posting window locked for Date T
    end note

    EOD_CUTOFF --> COB_PROCESSING: Draining Complete
    note right of COB_PROCESSING
        Phases 1-3: Core EOD Accounting Batch
        • Automated Below-Min ADB fee deductions
        • Daily deposit interest accrual & 20% BIR tax
        • Balance snapshot freezing & GL reconciliation
    end note

    COB_PROCESSING --> ROLLOVER: EOD Accounting Balanced
    note right of ROLLOVER
        Phase 4: Platform Rollover
        • business_date advanced (T ➔ T+1)
        • Daily customer velocity limits reset
        • EodCompletedEvent emitted to Kafka
    end note

    COB_PROCESSING --> ERROR_HALTED: Financial Imbalance or DB Lock Error
    note left of ERROR_HALTED
        Safety Tripwire:
        • Calendar rollover BLOCKED
        • Ops alerted
        • Ops invokes modular POST /eod/trigger to remediate
    end note

    ERROR_HALTED --> COB_PROCESSING: Issue Resolved & EOD Retried

    ROLLOVER --> ONLINE: Start-of-Business (SOB) Reopened
    note right of ONLINE
        • Posting window reopened for Date T+1
        • Buffered T+1 transfers executed
        • Regular 24/7 online trading resumes
    end note
```

---

## 4. End-to-End Operational Workflows

### 4.1 Scenario A: Automated Nightly COB Run (Happy Path)

* **Trigger**: Automated Scheduler fires at `00:00 UTC` via HTTP `POST /api/v1/cbs/cob/trigger`.
* **Execution**: Fully automated transition across Phases 0 through 4.

```mermaid
sequenceDiagram
    autonumber
    actor Scheduler as Docker/Cron Scheduler (00:00 UTC)
    participant COB as COB Controller (:8085)
    participant EOD as EOD Calculation Engine
    participant DB as Master DB (:1433)
    participant Kafka as Kafka Broker (:9092)
    participant Comp as Compliance Service (:8086)

    Scheduler->>COB: POST /api/v1/cbs/cob/trigger
    activate COB

    Note over COB,DB: Phase 0: Pre-COB Cutoff & Drain
    COB->>DB: UPDATE system_dates SET status = 'EOD_CUTOFF'
    COB->>Kafka: Publish PostingCutoffInitiatedEvent

    Note over COB,EOD: Delegation to EOD Accounting Engine
    COB->>EOD: Execute EOD Calculations for Date T
    activate EOD

    Note over EOD,DB: Phase 1: Automated Fee Deductions
    EOD->>DB: Deduct Below-Min ADB Fees (Zero-Overdraft Arrears to uncollected_fees)

    Note over EOD,DB: Phase 2: Daily Interest & Tax
    EOD->>DB: Accrue daily interest & apply 20% BIR withholding tax

    Note over EOD,DB: Phase 3: Balance Snapshots & GL Balancing
    EOD->>DB: INSERT INTO eod_balance_snapshots SELECT * FROM balance_master
    EOD->>DB: Validate SUM(Debits) == SUM(Credits) in gl_ledger
    EOD-->>COB: Accounting Batch Completed (Success)
    deactivate EOD

    Note over COB,DB: Phase 4: Business Date Rollover & SOB
    COB->>DB: UPDATE system_dates SET business_date = business_date + 1, status = 'ONLINE'
    COB->>DB: Reset daily velocity and withdrawal limit counters
    COB->>Kafka: Publish EodCompletedEvent & BalanceSnapshotFrozenEvent

    COB-->>Scheduler: HTTP 200 OK {status: "COMPLETED", newBusinessDate: "2026-10-08"}
    deactivate COB

    Note over Kafka,Comp: Asynchronous Nightly Artifact Generation
    par Async Document Processing
        Kafka->>Comp: Consume EodCompletedEvent
        Comp->>Comp: Compile Customer PDF E-Statements
        Comp->>Comp: Compile GL Trial Balance (Apache POI Excel/PDF)
        Comp->>Comp: Compile BIR Form 2306 Certificates
        Comp->>Comp: Generate AMLA CTR Filings (Tx >= 500k)
    end
```

---

### 4.2 Scenario B: Manual Error Recovery & Remediation

When a computational failure occurs (e.g., transient database lock conflict during interest accrual), the state machine halts in `ERROR_HALTED` to protect financial integrity:

```mermaid
sequenceDiagram
    autonumber
    actor Ops as Operations Admin (Portal)
    participant COB as COB Controller (:8085)
    participant EOD as EOD Engine (:8085)
    participant DB as Master DB (:1433)

    Ops->>COB: GET /api/v1/cbs/cob/status
    COB-->>Ops: HTTP 200 OK {systemState: "ERROR_HALTED", failedPhase: "PHASE_2_INTEREST", dateRolled: false}

    Note over Ops: Ops investigates lock conflict and clears blocking session

    Note over Ops,EOD: Step 1: Retry Pure EOD Calculations (Calendar remains on Date T)
    Ops->>EOD: POST /api/v1/cbs/eod/trigger {targetBusinessDate: "2026-10-07", targetModule: "INTEREST"}
    EOD->>DB: Accrue interest & apply 20% BIR tax
    EOD-->>Ops: HTTP 200 OK {status: "COMPLETED", modulesExecuted: ["DAILY_INTEREST_ACCRUALS"], dateRolled: false}

    Note over Ops,COB: Step 2: Resume Master COB Rollover
    Ops->>COB: POST /api/v1/cbs/cob/trigger {executionMode: "RESUME_AFTER_REPAIR"}
    COB->>DB: UPDATE system_dates SET business_date = business_date + 1, status = 'ONLINE'
    COB-->>Ops: HTTP 200 OK {status: "COMPLETED", newBusinessDate: "2026-10-08", systemState: "ONLINE"}
```

---

### 4.3 Scenario C: 24/7 Digital Intake During Cutoff Window

Customer mobile banking remains functional 24/7. Transactions submitted during the COB window are smoothly tagged with Value Date $T+1$:

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Customer Mobile App
    participant Orch as transfer-orchestrator (:8082)
    participant CBS as t24-mock-cbs (:8085)
    participant DB as Master DB (:1433)

    Customer->>Orch: POST /api/v1/transfers (₱5,000 to ACC-008541)
    Orch->>CBS: GET /api/v1/cbs/system-date
    CBS->>DB: SELECT status, business_date FROM system_dates
    DB-->>CBS: status = 'EOD_CUTOFF', business_date = '2026-10-07'
    CBS-->>Orch: {status: "EOD_CUTOFF", businessDate: "2026-10-07", postingWindowOpen: false}

    Note over Orch: Core Posting Window Closed for Date T!
    Note over Orch: Tag Transfer with Value Date = T+1 (2026-10-08)

    Orch->>CBS: POST /api/v1/cbs/transfers (OFS: VALUE.DATE=20261008, QUEUE_MODE=BUFFERED)
    CBS-->>Orch: OFS ACK // BUFFERED_FOR_T_PLUS_1 // QUEUED

    Orch-->>Customer: HTTP 202 Accepted {status: "QUEUED_FOR_T_PLUS_1", message: "Transaction accepted and scheduled for value date tomorrow."}
```

---

## 5. API Specification & Endpoint Strategy

### 5.1 Endpoint Overview Matrix

| Service | Port | Method | Endpoint Path | Caller | Primary Responsibility | Wire Format |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`t24-mock-cbs`** | `:8085` | `POST` | `/api/v1/cbs/cob/trigger` | Batch Scheduler / Ops Admin | Triggers master Close of Business platform lifecycle (Phases 0–4). | Native JSON |
| **`t24-mock-cbs`** | `:8085` | `GET` | `/api/v1/cbs/cob/status` | Ops Admin / Monitoring Tools | Polls real-time COB state machine, active phase, progress %, and error logs. | Native JSON |
| **`t24-mock-cbs`** | `:8085` | `POST` | `/api/v1/cbs/eod/trigger` | Internal COB / Ops Admin | Triggers idempotent financial calculations for Date $T$ without calendar rollover. | Native JSON |
| **`t24-mock-cbs`** | `:8085` | `GET` | `/api/v1/cbs/system-date` | `transfer-orchestrator` / Gateway | Queries current authoritative business date and posting window status. | Native JSON |

---

### 5.2 Endpoint Schemas

#### 5.2.1 [COB Architecture] `POST /api/v1/cbs/cob/trigger` (Master COB Pipeline Trigger)
* **Purpose**: Orchestrates the master Close of Business platform lifecycle from business date $T \to T+1$.
* **Request Payload**:
  ```json
  {
    "executionMode": "AUTOMATED_SCHEDULE",
    "targetBusinessDate": "2026-10-07",
    "operatorId": "SYSTEM_SCHEDULER"
  }
  ```
* **Response Payload (HTTP 200 OK)**:
  ```json
  {
    "status": "COMPLETED",
    "previousBusinessDate": "2026-10-07",
    "newBusinessDate": "2026-10-08",
    "phasesCompleted": [
      "PHASE_0_POSTING_CUTOFF",
      "PHASE_1_AUTOMATED_FEE_DEDUCTIONS",
      "PHASE_2_DAILY_INTEREST_ACCRUALS",
      "PHASE_3_SNAPSHOT_FREEZING",
      "PHASE_4_BUSINESS_DATE_ROLLOVER"
    ],
    "metrics": {
      "accountsProcessed": 14500,
      "totalFeesCollectedPhp": 85200.00,
      "totalUncollectedFeesLoggedPhp": 3400.00,
      "totalInterestAccruedPhp": 41250.00,
      "totalTaxWithheldBirPhp": 8250.00,
      "snapshotsFrozen": 14500
    },
    "systemState": "ONLINE",
    "executionDurationMs": 4210,
    "completedAtUtc": "2026-10-08T00:04:10Z"
  }
  ```

#### 5.2.2 [COB Architecture] `GET /api/v1/cbs/cob/status` (Active COB State Machine & Progress Status)
* **Purpose**: Real-time observability during long-running batch runs.
* **Request**: None (HTTP GET).
* **Response Payload (HTTP 200 OK)**:
  ```json
  {
    "businessDate": "2026-10-07",
    "systemState": "COB_PROCESSING",
    "currentPhase": "PHASE_2_DAILY_INTEREST_ACCRUALS",
    "progressPercent": 65,
    "startedAtUtc": "2026-10-08T00:00:02Z",
    "elapsedDurationMs": 2730,
    "operatorId": "SYSTEM_SCHEDULER",
    "isLocked": true,
    "lastError": null
  }
  ```

#### 5.2.3 [EOD Architecture] `POST /api/v1/cbs/eod/trigger` (Modular EOD Accounting Calculation Trigger)
* **Purpose**: Calculates fees, interest, and snapshots for Date $T$ without advancing the system calendar.
* **Request Payload**:
  ```json
  {
    "targetBusinessDate": "2026-10-07",
    "targetModule": "ALL",
    "operatorId": "OPS_BATCH_EXEC"
  }
  ```
* **Response Payload (HTTP 200 OK)**:
  ```json
  {
    "status": "COMPLETED",
    "businessDate": "2026-10-07",
    "modulesExecuted": [
      "AUTOMATED_FEE_DEDUCTIONS",
      "DAILY_INTEREST_ACCRUALS",
      "SNAPSHOT_FREEZING"
    ],
    "metrics": {
      "accountsProcessed": 14500,
      "totalFeesCollectedPhp": 85200.00,
      "totalUncollectedFeesLoggedPhp": 3400.00,
      "totalInterestAccruedPhp": 41250.00,
      "totalTaxWithheldBirPhp": 8250.00,
      "snapshotsFrozen": 14500
    },
    "dateRolled": false,
    "executionDurationMs": 3150,
    "completedAtUtc": "2026-10-08T00:03:15Z"
  }
  ```

#### 5.2.4 [COB Architecture] `GET /api/v1/cbs/system-date` (Authoritative Calendar & Posting Window Status)
* **Purpose**: Inquired by `transfer-orchestrator` to determine whether funds transfers post immediately or must be buffered.
* **Response Payload (HTTP 200 OK)**:
  ```json
  {
    "businessDate": "2026-10-07",
    "status": "ONLINE",
    "postingWindowOpen": true,
    "currentPhase": null,
    "lastRolloverAtUtc": "2026-10-07T00:04:21Z"
  }
  ```

---

## 6. Master Database Schema Entities (`t24-mock-cbs`)

### 6.1 COB Architecture Entities (Platform State & Batch Audit)

```sql
-- 1. Authoritative Core Calendar & Platform State (Governed by COB)
CREATE TABLE system_dates (
    id INT PRIMARY KEY DEFAULT 1,
    business_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ONLINE', 'EOD_CUTOFF', 'COB_PROCESSING', 'ROLLOVER', 'ERROR_HALTED')),
    posting_window_open BOOLEAN NOT NULL DEFAULT TRUE,
    last_cob_completed_at TIMESTAMP WITH TIME ZONE NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Master COB Operational Audit Log (Governed by COB)
CREATE TABLE cob_batch_log (
    batch_id BIGSERIAL PRIMARY KEY,
    business_date DATE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    status VARCHAR(20) NOT NULL, -- 'RUNNING', 'COMPLETED', 'FAILED'
    current_phase VARCHAR(50) NOT NULL,
    accounts_processed INT NOT NULL DEFAULT 0,
    total_fees_collected NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    total_interest_accrued NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    total_tax_withheld NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    error_message TEXT NULL
);
```

### 6.2 EOD Architecture Entities (Financial Ledgers, Arrears & Snapshots)

```sql
-- 3. Authoritative Closing Balance Snapshots (Generated by EOD Phase 3)
CREATE TABLE eod_balance_snapshots (
    snapshot_id BIGSERIAL PRIMARY KEY,
    business_date DATE NOT NULL,
    account_id VARCHAR(32) NOT NULL,
    closing_ledger_balance NUMERIC(18,2) NOT NULL,
    closing_available_balance NUMERIC(18,2) NOT NULL,
    accrued_interest_ytd NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    frozen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_account_date UNIQUE (account_id, business_date)
);

-- 4. Zero-Overdraft Arrears Tracking (Generated by EOD Phase 1 Fees)
CREATE TABLE uncollected_fees (
    arrears_id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(32) NOT NULL,
    fee_type VARCHAR(30) NOT NULL, -- 'BELOW_MIN_ADB', 'DORMANCY'
    amount_due NUMERIC(18,2) NOT NULL,
    amount_collected NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    amount_unpaid NUMERIC(18,2) NOT NULL,
    business_date DATE NOT NULL,
    is_settled BOOLEAN NOT NULL DEFAULT FALSE,
    logged_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

---

## 7. Architectural Invariants & Non-Negotiables

1. **Inviolable Date Advancement Rule**: The authoritative calendar date in `system_dates` must **NEVER** advance to $T+1$ if any EOD calculation phase fails or if the General Ledger does not balance ($\sum \text{Debits} \ne \sum \text{Credits}$).
2. **Zero-Overdraft Maintenance Fee Invariant**: Deducting below-min ADB maintenance fees must never drive a customer account into an unarranged negative balance. If an account has $\text{Balance} < \text{Fee}$, the CBS deducts only available funds down to 0.00 and logs the unpaid balance to `uncollected_fees`.
3. **BIR 20% Withholding Tax Invariant**: On month-end interest capitalization, the CBS must split gross interest: crediting exactly 80% to the customer liability balance and crediting 20% directly to the BIR Tax Withholding Payable General Ledger account (`GL-2401`).
4. **24/7 Channel Non-Disruption**: Retail channels are never rejected with hard 500 errors during COB. Transfers arriving during `EOD_CUTOFF` receive `HTTP 202 Accepted` and are value-dated for next business day settlement ($T+1$).
