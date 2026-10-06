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
        AuditWorker["Audit Consumer Worker<br/>(audit-vault-workers group)"]
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
    KafkaBatch -->|"Consume Batch Events"| AuditWorker
    AuditWorker -->|"Append-Only Audit Log"| PostgresAudit
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
    participant AuditWorker as Audit Vault Consumer
    participant AuditVault as Postgres Audit (:5432)

    %% PHASE 0: INITIATION & CUTOFF
    rect rgb(240, 248, 255)
    Note over Operator,AuditVault: Phase 0: Cutoff Initiation & In-Flight Drainage
    Operator->>Gateway: POST /api/v1/batch/eod/start { valueDate: "2026-10-05" }
    Gateway->>Orch: Forward EOD Batch Trigger
    Note over Orch: Drain daytime in-flight transfers and queue T+1 traffic
    
    Note over Orch: Rule 1: Translate Cutoff instruction to Temenos OFS wire syntax
    Orch->>Orch: Map to OFS: BATCH.JOB,CUTOFF/I/PROCESS,,VALUE.DATE=20261005
    Orch->>CBS: POST /api/v1/internal/cbs/ofs-command (Payload: BATCH.JOB,CUTOFF wire string)
    
    CBS->>AzureSQL: SELECT status FROM system_dates WITH (UPDLOCK)
    AzureSQL-->>CBS: status = "ONLINE"
    CBS->>AzureSQL: UPDATE system_dates SET status = "EOD_CUTOFF"
    Note over CBS,AzureSQL: Rule 3: Record batch event into outbox_events within transaction
    CBS->>AzureSQL: INSERT INTO outbox_events (event_type: "EOD_CUTOFF_INITIATED", aggregate_id: "BATCH-20261005", status: "PENDING")
    CBS-->>Orch: 200 OK (OFS: BATCH-CUTOFF//1/SUCCESS,STATUS=EOD_CUTOFF)

    Note over CBS,Kafka: Rule 3: CBS publishes batch cutoff event directly to Kafka from outbox_events
    CBS->>Kafka: Publish EodCutoffInitiatedEvent (valueDate: 2026-10-05)
    CBS->>AzureSQL: UPDATE outbox_events SET status = "PUBLISHED", published_at = SYSUTCDATETIME() WHERE aggregate_id = "BATCH-20261005" AND status = "PENDING"
    end

    %% PHASE 1: SUBFEATURE 4.2 FEES
    rect rgb(255, 250, 240)
    Note over Orch,AzureSQL: Phase 1: Subfeature 4.2 - Automated Batch Fees Assessment
    Note over Orch: Rule 1: Translate Fee execution to Temenos OFS wire syntax
    Orch->>Orch: Map to OFS: AC.CHARGE,BATCH/I/PROCESS,,VALUE.DATE=20261005
    Orch->>CBS: POST /api/v1/internal/cbs/ofs-command (Payload: AC.CHARGE,BATCH wire string)
    
    CBS->>AzureSQL: SELECT candidate accounts for maintenance, below-min ADB, and dormancy fees
    AzureSQL-->>CBS: Candidate account records
    loop For each fee-eligible account
        CBS->>AzureSQL: BEGIN TRANSACTION
        CBS->>AzureSQL: SELECT balance_amount, hold_amount FROM balance_master WITH (UPDLOCK, ROWLOCK) WHERE account_id = ?
        CBS->>AzureSQL: UPDATE balance_master (balance_amount -= feeAmount)
        CBS->>AzureSQL: INSERT INTO transactions (type: "FEE", ref: "FEE-...")
        CBS->>AzureSQL: INSERT INTO gl_ledger (DR: CustomerAcct, CR: GL-4100-FEE-INCOME)
        opt Partial Deduction or Zero Balance
            CBS->>AzureSQL: INSERT INTO uncollected_fees (record unpaid arrears)
        end
        Note over CBS,AzureSQL: Rule 3: Record fee event into outbox_events within ACID transaction
        CBS->>AzureSQL: INSERT INTO outbox_events (event_type: "FEE_DEDUCTED", aggregate_id: "BATCH-FEE-20261005", status: "PENDING")
        CBS->>AzureSQL: COMMIT TRANSACTION
    end
    CBS-->>Orch: 200 OK (OFS: AC.CHARGE-BATCH//1/SUCCESS,PROCESSED=142,TOTAL_FEES=71000.00,ARREARS=300.00)

    Note over CBS,Kafka: Rule 3: CBS publishes fee events directly to Kafka from outbox_events
    CBS->>Kafka: Publish FeeDeductedEvent (processedCount: 142, totalFees: 71000.00 PHP)
    CBS->>AzureSQL: UPDATE outbox_events SET status = "PUBLISHED", published_at = SYSUTCDATETIME() WHERE aggregate_id = "BATCH-FEE-20261005" AND status = "PENDING"
    end

    %% PHASE 2: SUBFEATURE 4.3 INTEREST
    rect rgb(245, 255, 245)
    Note over Orch,AzureSQL: Phase 2: Subfeature 4.3 - Interest Accruals & Capitalization
    Note over Orch: Rule 1: Translate Interest execution to Temenos OFS wire syntax
    Orch->>Orch: Map to OFS: IC.CHARGE,BATCH/I/PROCESS,,VALUE.DATE=20261005,PERIOD=2026-10
    Orch->>CBS: POST /api/v1/internal/cbs/ofs-command (Payload: IC.CHARGE,BATCH wire string)

    CBS->>AzureSQL: SELECT active deposit accounts with interest rates
    AzureSQL-->>CBS: Account balances and rate configurations
    loop Daily Accrual Calculation
        CBS->>AzureSQL: INSERT/UPDATE interest_accruals (accruedAmount += dailyInterest)
        CBS->>AzureSQL: INSERT INTO gl_ledger (DR: GL-5100-INT-EXP, CR: GL-2200-INT-PAYABLE)
    end
    opt Month-End / Capitalization Date
        loop Capitalization & Withholding Tax
            CBS->>AzureSQL: BEGIN TRANSACTION
            CBS->>AzureSQL: SELECT balance_amount, accrued_interest FROM balance_master WITH (UPDLOCK, ROWLOCK) WHERE account_id = ?
            Note over CBS: Compute 20% Withholding Tax (Net Interest = Gross - Tax)
            CBS->>AzureSQL: UPDATE balance_master (balance_amount += netInterest)
            CBS->>AzureSQL: INSERT INTO transactions (type: "INTEREST_CREDIT")
            CBS->>AzureSQL: INSERT INTO transactions (type: "WITHHOLDING_TAX")
            CBS->>AzureSQL: INSERT INTO gl_ledger (DR: GL-2200-INT-PAYABLE, CR: CustomerAcct, CR: GL-2300-WHT-PAYABLE)
            CBS->>AzureSQL: UPDATE interest_accruals SET is_capitalized = 1 WHERE account_id = ?
            Note over CBS,AzureSQL: Rule 3: Record interest event into outbox_events within ACID transaction
            CBS->>AzureSQL: INSERT INTO outbox_events (event_type: "INTEREST_CAPITALIZED", aggregate_id: "BATCH-INT-20261005", status: "PENDING")
            CBS->>AzureSQL: COMMIT TRANSACTION
        end
    end
    CBS-->>Orch: 200 OK (OFS: IC.CHARGE-BATCH//1/SUCCESS,PROCESSED=12000,NET_CREDITED=2038368.00,TAX_WITHHELD=509592.00)

    Note over CBS,Kafka: Rule 3: CBS publishes interest events directly to Kafka from outbox_events
    CBS->>Kafka: Publish InterestCapitalizedEvent (capitalizedAccounts: 12000, totalNetCredited: 2038368.00 PHP)
    CBS->>AzureSQL: UPDATE outbox_events SET status = "PUBLISHED", published_at = SYSUTCDATETIME() WHERE aggregate_id = "BATCH-INT-20261005" AND status = "PENDING"
    end

    %% PHASE 3: SUBFEATURE 4.1 REPORTS & RECONCILIATION
    rect rgb(255, 245, 250)
    Note over Orch,AzureSQL: Phase 3: Subfeature 4.1 - Balance Rollup, GL Trial Balance & Reports
    Note over Orch: Rule 1: Translate Report generation to Temenos OFS wire syntax
    Orch->>Orch: Map to OFS: GL.REPORT,GENERATE/I/PROCESS,,VALUE.DATE=20261005
    Orch->>CBS: POST /api/v1/internal/cbs/ofs-command (Payload: GL.REPORT,GENERATE wire string)

    CBS->>AzureSQL: SELECT SUM(debit_amount), SUM(credit_amount) FROM gl_ledger WHERE posting_date = '2026-10-05'
    AzureSQL-->>CBS: { totalDebits: 14500000.0000, totalCredits: 14500000.0000 }
    Note over CBS: Validate Zero-Sum GL Equation: Debits equal Credits (Passed)
    CBS->>AzureSQL: INSERT INTO eod_balance_snapshots (account_id, closing_balance, held_amount, snapshot_date)
    CBS->>AzureSQL: INSERT INTO eod_reports_metadata (report_type, status, record_count, storage_uri, file_sha256_hash)
    Note over CBS,AzureSQL: Rule 3: Record reports event into outbox_events
    CBS->>AzureSQL: INSERT INTO outbox_events (event_type: "REPORTS_READY", aggregate_id: "BATCH-REP-20261005", status: "PENDING")
    CBS-->>Orch: 200 OK (OFS: GL.REPORT//1/SUCCESS,BALANCED=YES,REPORT_COUNT=4)

    Note over CBS,Kafka: Rule 3: CBS publishes reports event directly to Kafka from outbox_events
    CBS->>Kafka: Publish ReportsReadyEvent (date: 2026-10-05, glBalanced: true, reportIds: ["GL_TRIAL_BAL", "TXN_JOURNAL", "AMLA_CTR", "EOD_SUMMARY"])
    CBS->>AzureSQL: UPDATE outbox_events SET status = "PUBLISHED", published_at = SYSUTCDATETIME() WHERE aggregate_id = "BATCH-REP-20261005" AND status = "PENDING"
    end

    %% PHASE 4: ROLLOVER & REOPEN
    rect rgb(240, 255, 255)
    Note over Orch,AuditVault: Phase 4: Business Date Rollover & System Reopen
    Note over Orch: Rule 1: Translate Rollover instruction to Temenos OFS wire syntax
    Orch->>Orch: Map to OFS: DATES,ROLLOVER/I/PROCESS,,FROM.DATE=20261005,TO.DATE=20261006
    Orch->>CBS: POST /api/v1/internal/cbs/ofs-command (Payload: DATES,ROLLOVER wire string)

    CBS->>AzureSQL: UPDATE system_dates SET business_date = '2026-10-06', status = 'ONLINE'
    Note over CBS,AzureSQL: Rule 3: Record rollover event into outbox_events within transaction
    CBS->>AzureSQL: INSERT INTO outbox_events (event_type: "EOD_COMPLETED", aggregate_id: "BATCH-EOD-20261005", status: "PENDING")
    AzureSQL-->>CBS: Date Rollover Committed
    CBS-->>Orch: 200 OK (OFS: DATES-ROLLOVER//1/SUCCESS,NEW.DATE=20261006,STATUS=ONLINE)

    Note over CBS,Kafka: Rule 3: CBS publishes EOD completion event directly to Kafka from outbox_events
    CBS->>Kafka: Publish EodCompletedEvent (date: 2026-10-05, nextDate: 2026-10-06, status: SUCCESS)
    CBS->>AzureSQL: UPDATE outbox_events SET status = "PUBLISHED", published_at = SYSUTCDATETIME() WHERE aggregate_id = "BATCH-EOD-20261005" AND status = "PENDING"
    
    Orch-->>Gateway: 200 OK (BatchExecutionSummary: status = EOD_COMPLETED, durationSec = 142)
    Gateway-->>Operator: 200 OK (EOD Pipeline Completed Successfully)
    end

    %% DOWNSTREAM EVENT CONSUMPTION (RULE 2: AUDIT WORKER PERSISTS TO POSTGRES)
    par Asynchronous Notification & Statement Delivery
        Kafka->>Notif: Consume FeeDeductedEvent and InterestCapitalizedEvent
        Notif-->>Notif: Generate HTML Advices and Dispatch Email via MailHog
        Kafka->>Notif: Consume ReportsReadyEvent
        Notif-->>Notif: Generate and Dispatch Monthly Customer E-Statements
    and Rule 2: Immutable Compliance Projection via Audit Worker
        Kafka->>AuditWorker: Consume All Batch Events
        AuditWorker->>AuditVault: INSERT INTO ledger_mutation_audit (Append-Only Audit Log)
    end
```

---

## 5. Detailed Subfeature Documentation Links

For comprehensive breakdowns, data contracts, and dedicated sequence/swimlane diagrams for each subfeature, refer to:

- [Subfeature 4.1: Reports Architecture](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/eod_subfeature_reports.md)
- [Subfeature 4.2: Fees Architecture](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/eod_subfeature_fees.md)
- [Subfeature 4.3: Interest Calculations Architecture](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/eod_subfeature_interest.md)
