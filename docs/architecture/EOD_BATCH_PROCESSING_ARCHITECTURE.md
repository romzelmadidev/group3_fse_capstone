# End-of-Day (EOD) & Batch Processing Architecture: Master Workflow

This document provides the architectural specification, swimlane process models, and sequence diagrams for **Feature 4: End-of-Day (EOD) & Batch Processing** within the revised Core Banking Platform architecture (`banking-platform-architecture.html`).

---

## 1. Architectural Context & System Evolution

In the revised platform architecture:
- **Ledger Mutation Ownership**: Balance mutations, account locking, and double-entry accounting logic have moved from the `transfer-orchestrator` (:8082) into the **T24 Mock CBS** (:8085).
- **Primary Data Store**: T24 Mock CBS maintains exclusive, direct connectivity to **Azure SQL Database** (:1433) for master account ledgers, balances, and transaction journals using ACID transactions with row-level pessimistic locks (`SELECT ... WITH (UPDLOCK, ROWLOCK)`).
- **Transfer Orchestrator Role**: Acts as the transaction intake gateway, risk engine coordinator, and daytime flow controller. During EOD batch windows, the orchestrator enforces the **EOD Cutoff**, temporarily queuing or value-dating new transfers to the next business date ($T+1$).
- **Decoupled Downstream Workers**:
  - **Apache Kafka** (:9092) transports asynchronous batch state events (`banking.batch.events`).
  - **Notification Service** (:8083) delivers customer statements, fee advices, and interest credit receipts.
  - **Azure PostgreSQL** (:5432) serves as an immutable, append-only **Audit Vault** (`ledger_mutation_audit`).

---

## 2. Overall EOD Batch Lifecycle (Feature 4 Master)

The End-of-Day batch processing run follows five strictly sequential execution phases:

1. **Phase 0: Posting Date Cutoff & Channel Freeze**: Orchestrator pauses $T$ transactional intake; pending in-flight transactions are cleared.
2. **Phase 1: Fee Assessment & Deductions (Subfeature 4.2)**: Automated debiting of monthly maintenance, low-balance penalties, and dormancy fees.
3. **Phase 2: Interest Calculations & Capitalization (Subfeature 4.3)**: Daily interest accrual and monthly net interest capitalization with 20% Final Withholding Tax.
4. **Phase 3: Balance Rollup, GL Reconciliation & Reports (Subfeature 4.1)**: Double-entry trial balance validation ($\sum \text{Debits} = \sum \text{Credits}$) and generation of daily journals, balance snapshots, e-statements, and AMLA CTR compliance files.
5. **Phase 4: Business Date Rollover & System Reopen**: Business date advances from $T$ to $T+1$; CBS and Orchestrator resume daytime STP online processing.

---

## 3. Master EOD Swimlane Diagram

```mermaid
flowchart TD
    %% ==========================================
    %% SWIMLANE: OPERATIONS & SCHEDULER
    %% ==========================================
    subgraph Lane_Ops["Operator & Automated Scheduler Tier"]
        Scheduler["Automated Cron / Scheduler<br/>(Trigger 23:59:00 PST)"]
        AdminPortal["Admin Operations Console<br/>(React 18 / Vite :3000)"]
        BatchMonitor["Batch Progress Monitor<br/>(Live Status Dashboard)"]
    end

    %% ==========================================
    %% SWIMLANE: PERIMETER & ORCHESTRATION
    %% ==========================================
    subgraph Lane_Orch["Perimeter & Orchestration Tier"]
        APIGateway["API Gateway (:8080)<br/>Spring Cloud Gateway"]
        Orchestrator["Transfer Orchestrator (:8082)<br/>Cutoff State Controller"]
        QueueBuffer["Cutoff Transaction Queue<br/>(Value Date: T+1 Buffer)"]
    end

    %% ==========================================
    %% SWIMLANE: T24 MOCK CBS ENGINE
    %% ==========================================
    subgraph Lane_CBS["T24 Mock CBS (:8085) - Core Banking Engine"]
        EodMaster["EOD Batch Master Controller<br/>(Spring Batch Job Launcher)"]
        CutoffStep["Step 1: In-Flight Drain & Cutoff"]
        FeeEngine["Step 2: Automated Fee Engine<br/>(Subfeature 4.2)"]
        InterestEngine["Step 3: Interest & Tax Engine<br/>(Subfeature 4.3)"]
        ReconEngine["Step 4: GL Recon & Reports Engine<br/>(Subfeature 4.1)"]
        RolloverStep["Step 5: Business Date Rollover (T -> T+1)"]
    end

    %% ==========================================
    %% SWIMLANE: AZURE SQL MASTER STORAGE
    %% ==========================================
    subgraph Lane_SQL["Azure SQL Database (:1433) - Master Ledgers"]
        SysDateTable[("system_dates<br/>(Status & Value Date)")]
        BalanceMaster[("balance_master<br/>(Pessimistic Row Locks)")]
        GlLedger[("gl_ledger & gl_balances<br/>(Double-Entry Accounts)")]
        ReportStore[("batch_reports & audit_logs<br/>(Metadata & Snapshot Storage)")]
    end

    %% ==========================================
    %% SWIMLANE: EVENT STREAMING
    %% ==========================================
    subgraph Lane_Kafka["Apache Kafka (:9092) - Event Broker"]
        KafkaBatch["Topic: banking.batch.events<br/>(Cutoff, Fees, Interest, Reports)"]
    end

    %% ==========================================
    %% SWIMLANE: DOWNSTREAM SERVICES
    %% ==========================================
    subgraph Lane_Downstream["Downstream Consumers Tier"]
        NotifService["Notification Service (:8083)<br/>(E-Statements, Fee & Tax Advices)"]
        PostgresAudit[("Azure PostgreSQL (:5432)<br/>Immutable Audit Vault")]
    end

    %% PROCESS FLOW CONNECTIONS
    Scheduler -->|"1. Trigger EOD Job"| EodMaster
    AdminPortal -->|"1b. Manual Trigger / Override"| APIGateway
    APIGateway -->|"Forward Batch Request"| EodMaster

    EodMaster -->|"2. Signal Cutoff Start"| Orchestrator
    Orchestrator -->|"Hold Daytime Traffic"| QueueBuffer
    EodMaster --> CutoffStep

    CutoffStep -->|"3. Update Status: EOD_PROCESSING"| SysDateTable
    CutoffStep -->|"Drain Verified"| FeeEngine

    FeeEngine -->|"4. Assess Maintenance & Dormancy Fees"| BalanceMaster
    FeeEngine -->|"Post Fee Income Entries"| GlLedger
    FeeEngine -->|"Publish Fee Events"| KafkaBatch
    FeeEngine -->|"Fees Completed"| InterestEngine

    InterestEngine -->|"5. Daily Accrual & Monthly Capitalization"| BalanceMaster
    InterestEngine -->|"Post Interest Expense & Tax Payable"| GlLedger
    InterestEngine -->|"Publish Interest Events"| KafkaBatch
    InterestEngine -->|"Interest Completed"| ReconEngine

    ReconEngine -->|"6. Query Balances & Validate Sum(Debit)=Sum(Credit)"| GlLedger
    ReconEngine -->|"Persist Snapshots & Report Records"| ReportStore
    ReconEngine -->|"Publish ReportsReadyEvent"| KafkaBatch
    ReconEngine -->|"Recon Passed"| RolloverStep

    RolloverStep -->|"7. Advance Date: T+1 & Status: ONLINE"| SysDateTable
    RolloverStep -->|"8. Signal Cutoff Finished"| Orchestrator
    Orchestrator -->|"Drain Buffered Transactions (Date T+1)"| QueueBuffer
    RolloverStep -->|"Publish EodCompletedEvent"| KafkaBatch

    EodMaster -.->|"Progress Telemetry"| BatchMonitor

    KafkaBatch -->|"Fan-Out Events"| NotifService
    KafkaBatch -->|"Append-Only Audit Log"| PostgresAudit
```

---

## 4. Master EOD Sequence Diagram (Request / Response Flow)

```mermaid
sequenceDiagram
    autonumber
    actor Operator as Batch Operator / Scheduler
    participant Gateway as API Gateway (:8080)
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant AzureSQL as Azure SQL DB (:1433)
    participant Kafka as Kafka Broker (:9092)
    participant Notif as Notification Svc (:8083)
    participant AuditVault as Postgres Audit (:5432)

    %% PHASE 0: INITIATION & CUTOFF
    rect rgb(240, 248, 255)
    Note over Operator,AuditVault: Phase 0: Cutoff Initiation & In-Flight Drainage
    Operator->>Orch: POST /api/v1/batch/eod/start { valueDate: "2026-10-05" }
    Note over Orch: Drain daytime in-flight transfers and queue T+1 traffic
    Orch->>CBS: POST /api/v1/internal/cbs/batch/cutoff-start
    CBS->>AzureSQL: SELECT status FROM system_dates WITH (UPDLOCK)
    AzureSQL-->>CBS: status = "ONLINE"
    CBS->>AzureSQL: UPDATE system_dates SET status = "EOD_CUTOFF"
    CBS-->>Orch: 200 OK (Cutoff Active, Ready for Batch)
    Note over Orch,Kafka: Orchestrator publishes EOD events to Kafka on behalf of isolated CBS
    Orch->>Kafka: Publish EodCutoffInitiatedEvent
    end

    %% PHASE 1: SUBFEATURE 4.2 FEES
    rect rgb(255, 250, 240)
    Note over Orch,AzureSQL: Phase 1: Subfeature 4.2 - Automated Batch Fees Assessment
    Orch->>CBS: POST /api/v1/internal/cbs/batch/fees/execute
    CBS->>AzureSQL: SELECT accounts needing Maintenance / Below-Min / Dormancy Fees
    AzureSQL-->>CBS: List of fee-eligible accounts
    loop For each fee-eligible account
        CBS->>AzureSQL: BEGIN TX: SELECT balance WITH (UPDLOCK, ROWLOCK)
        CBS->>AzureSQL: UPDATE balance_master (balance -= feeAmount)
        CBS->>AzureSQL: INSERT INTO transactions (type: "FEE", ref: "FEE-...")
        CBS->>AzureSQL: INSERT INTO gl_ledger (DR: CustomerAcct, CR: GL-4100-FEE-INCOME)
        CBS->>AzureSQL: COMMIT TX
    end
    CBS-->>Orch: 200 OK (Fees Assessed, totalCount: 142)
    Orch->>Kafka: Publish FeeDeductedEvent
    end

    %% PHASE 2: SUBFEATURE 4.3 INTEREST
    rect rgb(245, 255, 245)
    Note over Orch,AzureSQL: Phase 2: Subfeature 4.3 - Interest Accruals & Capitalization
    Orch->>CBS: POST /api/v1/internal/cbs/batch/interest/execute
    CBS->>AzureSQL: SELECT active deposit accounts with interest rates
    AzureSQL-->>CBS: Account balances & rate configurations
    loop Daily Accrual Calculation
        CBS->>AzureSQL: INSERT/UPDATE interest_accruals (accruedAmount += dailyInterest)
        CBS->>AzureSQL: INSERT INTO gl_ledger (DR: GL-5100-INT-EXP, CR: GL-2200-INT-PAYABLE)
    end
    opt Month-End / Capitalization Date
        loop Capitalization & Withholding Tax
            CBS->>AzureSQL: BEGIN TX: SELECT balance, accrued_interest WITH (UPDLOCK, ROWLOCK)
            Note over CBS: Compute 20% Withholding Tax<br/>Net Interest = Gross - Tax
            CBS->>AzureSQL: UPDATE balance_master (balance += netInterest)
            CBS->>AzureSQL: INSERT INTO transactions (type: "INTEREST_CREDIT")
            CBS->>AzureSQL: INSERT INTO transactions (type: "WITHHOLDING_TAX")
            CBS->>AzureSQL: INSERT INTO gl_ledger (DR: GL-2200-INT-PAYABLE, CR: CustomerAcct, CR: GL-2300-WHT-PAYABLE)
            CBS->>AzureSQL: COMMIT TX
        end
    end
    CBS-->>Orch: 200 OK (Interest Processing Complete)
    Orch->>Kafka: Publish InterestCapitalizedEvent
    end

    %% PHASE 3: SUBFEATURE 4.1 REPORTS & RECONCILIATION
    rect rgb(255, 245, 250)
    Note over Orch,AzureSQL: Phase 3: Subfeature 4.1 - Balance Rollup, GL Trial Balance & Reports
    Orch->>CBS: POST /api/v1/internal/cbs/batch/reports/generate
    CBS->>AzureSQL: SELECT SUM(debit), SUM(credit) FROM gl_ledger WHERE date = '2026-10-05'
    AzureSQL-->>CBS: { totalDebits: 14500000.0000, totalCredits: 14500000.0000 }
    Note over CBS: Validate Zero-Sum GL Equation: Debits == Credits (Passed)
    CBS->>AzureSQL: INSERT INTO eod_balance_snapshots (account_id, closing_balance, date)
    CBS->>AzureSQL: INSERT INTO batch_reports_metadata (reportType, status, recordCount)
    CBS-->>Orch: 200 OK (Reports Generated, reportIds: ["GL_TRIAL_BAL", "TXN_JOURNAL", "AMLA_CTR"])
    Orch->>Kafka: Publish ReportsReadyEvent { reportIds: ["GL_TRIAL_BAL", "TXN_JOURNAL", "AMLA_CTR"] }
    end

    %% PHASE 4: ROLLOVER & REOPEN
    rect rgb(240, 255, 255)
    Note over Orch,AuditVault: Phase 4: Business Date Rollover & System Reopen
    Orch->>CBS: POST /api/v1/internal/cbs/batch/rollover/execute
    CBS->>AzureSQL: UPDATE system_dates SET business_date = '2026-10-06', status = 'ONLINE'
    AzureSQL-->>CBS: Date Rollover Committed
    CBS-->>Orch: 200 OK (Rollover Complete, newBusinessDate: '2026-10-06')
    Orch->>Kafka: Publish EodCompletedEvent { date: '2026-10-05', nextDate: '2026-10-06', status: 'SUCCESS' }
    Orch-->>Operator: 200 OK { status: "EOD_COMPLETED", durationSec: 142 }
    end

    %% DOWNSTREAM EVENT CONSUMPTION
    par Asynchronous Notification & Audit
        Kafka->>Notif: Consume FeeDeductedEvent & InterestCapitalizedEvent
        Notif-->>Notif: Generate HTML Advices & Dispatch Email via MailHog
        Kafka->>Notif: Consume ReportsReadyEvent
        Notif-->>Notif: Generate & Dispatch Monthly E-Statements
    and Immutable Compliance Projection
        Kafka->>AuditVault: Consume All Batch Events
        AuditVault->>AuditVault: INSERT INTO ledger_mutation_audit (Append-Only)
    end
```

---

## 5. Detailed Subfeature Documentation Links

For comprehensive breakdowns, data contracts, and dedicated sequence/swimlane diagrams for each subfeature, refer to:

- [Subfeature 4.1: Reports Architecture](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/eod_subfeature_reports.md)
- [Subfeature 4.2: Fees Architecture](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/eod_subfeature_fees.md)
- [Subfeature 4.3: Interest Calculations Architecture](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/eod_subfeature_interest.md)
