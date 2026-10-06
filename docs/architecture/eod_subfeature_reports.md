# Subfeature 4.1: End-of-Day (EOD) Reports & GL Reconciliation Architecture

This document defines the technical architecture, swimlane process models, request/response sequence flows, and data schemas for **Subfeature 4.1: Reports** within the **End-of-Day (EOD) / Batch Processing** engine.

---

## 1. Subfeature Scope & Functional Requirements

At the conclusion of the daily posting cycle, **T24 Mock CBS** (:8085) generates core operational, financial, and compliance reports directly against the **Azure SQL Database** master ledgers:

1. **General Ledger (GL) Trial Balance & Reconciliation Report**:
   - Aggregates debits and credits across all internal bank chart of accounts (`gl_accounts`, `gl_ledger`).
   - Verifies the fundamental accounting equation: $\sum \text{Debits} \equiv \sum \text{Credits}$.
   - Flags out-of-balance exceptions immediately with batch halt warnings.
2. **Daily Transaction Journal**:
   - Comprehensive audit roll of all posted transactions on business date $T$ (transfers, reversals, fee charges, interest credits, tax withholdings).
   - Records transaction reference, timestamp, source/target accounts, currency, and operator/system channel.
3. **EOD Account Balance Snapshot**:
   - Freezes immutable closing ledger balances, hold amounts, and available funds into `eod_balance_snapshots`.
   - Used for historical balance inquiry, audit verification, and average daily balance (ADB) calculations.
4. **Periodic Customer E-Statements**:
   - Identifies accounts matching today's monthly statement cycle cutoff.
   - Compiles monthly transaction history, opening/closing balance, interest earned, and withholding tax withheld.
   - Emits Kafka events for the **Notification Service** (:8083) to generate PDF statements and dispatch email advisories.
5. **Regulatory AMLA Covered Transaction Report (CTR)**:
   - Identifies all transactions exceeding the Anti-Money Laundering Act (AMLA) threshold of 500,000.00 PHP (or aggregated structuring).
   - Compiles structured compliance data ready for AMLC regulator extraction.
6. **Batch Execution & Exception Summary**:
   - Logs overall batch run statistics, record throughput, execution duration, and uncollected fee exceptions.

---

## 2. Reports Generation Swimlane Diagram

```mermaid
flowchart TD
    %% ==========================================
    %% SWIMLANE: BATCH ORCHESTRATOR
    %% ==========================================
    subgraph Lane_Trigger["Batch Orchestrator Tier"]
        StepTrigger["EOD Step 4 Trigger<br/>(ReconEngine Activated)"]
        BatchStatus["Batch Status Registry<br/>(Track Step Progress)"]
    end

    %% ==========================================
    %% SWIMLANE: T24 CBS REPORTING ENGINE
    %% ==========================================
    subgraph Lane_CBS["T24 Mock CBS (:8085) - Core Banking Engine"]
        DataExtractor["1. Data Extractor & Balance Rollup<br/>(Read Transactions & Balances)"]
        GLValidator["2. GL Trial Balance Engine<br/>(Check Sum Debits == Sum Credits)"]
        StatementGen["3. Customer Statement Engine<br/>(Filter Cycle Cutoff Accounts)"]
        AmlaClassifier["4. AMLA Compliance Classifier<br/>(Filter Transactions >= 500k PHP)"]
        ReportCompiler["5. Report Document Formatter<br/>(Compile JSON / CSV / PDF Metadata)"]
    end

    %% ==========================================
    %% SWIMLANE: AZURE SQL MASTER STORAGE
    %% ==========================================
    subgraph Lane_SQL["Azure SQL Database (:1433) - Master Ledgers"]
        GLTable[("gl_ledger & gl_balances<br/>(Double-Entry Accounts)")]
        TxTable[("transactions<br/>(Financial Journals)")]
        BalTable[("balance_master<br/>(Closing Balances)")]
        SnapshotTable[("eod_balance_snapshots<br/>(Historical Ledger State)")]
        ReportMetaTable[("eod_reports_metadata<br/>(Status, Hashes & Paths)")]
    end

    %% ==========================================
    %% SWIMLANE: EVENT STREAMING
    %% ==========================================
    subgraph Lane_Kafka["Apache Kafka (:9092) - Event Stream"]
        KafkaReports["Topic: banking.batch.events<br/>Event: ReportsReadyEvent<br/>Event: StatementGeneratedEvent"]
    end

    %% ==========================================
    %% SWIMLANE: CONSUMERS & ARCHIVAL
    %% ==========================================
    subgraph Lane_Consumers["Downstream Delivery & Compliance Vault"]
        NotifService["Notification Service (:8083)<br/>(Thymeleaf PDF / Email Dispatch)"]
        AuditVault[("Azure PostgreSQL (:5432)<br/>Immutable Compliance Vault")]
        AdminViewer["Admin Portal (:3000)<br/>(Download EOD Reports)"]
    end

    %% PROCESS CONNECTIONS
    StepTrigger --> DataExtractor
    DataExtractor -->|"Query Day T Postings"| TxTable
    DataExtractor -->|"Query Closing Balances"| BalTable
    DataExtractor -->|"Persist Frozen Balances"| SnapshotTable
    DataExtractor --> GLValidator

    GLValidator -->|"Query GL Sums"| GLTable
    GLValidator -->|"Reconciliation Success"| StatementGen
    GLValidator -.->|"Out-of-Balance Error"| BatchStatus

    StatementGen -->|"Fetch Customer Statements"| TxTable
    StatementGen --> AmlaClassifier

    AmlaClassifier -->|"Filter High-Value Records"| TxTable
    AmlaClassifier --> ReportCompiler

    ReportCompiler -->|"Store Report Metadata & Hashes"| ReportMetaTable
    ReportCompiler -->|"Publish Report Completion"| KafkaReports
    ReportCompiler --> BatchStatus

    KafkaReports -->|"Statement Dispatch"| NotifService
    KafkaReports -->|"Audit Record Projection"| AuditVault
    ReportMetaTable -.->|"Query Report Files"| AdminViewer
```

---

## 3. Reports Request / Response Sequence Flow

```mermaid
sequenceDiagram
    autonumber
    participant BatchJob as EOD Batch Coordinator
    participant CBS as T24 Mock CBS (:8085)
    participant AzureSQL as Azure SQL DB (:1433)
    participant Storage as Report Document Vault
    participant Kafka as Kafka Broker (:9092)
    participant Notif as Notification Svc (:8083)
    participant AuditWorker as Audit Vault Consumer
    participant AuditVault as Postgres Audit (:5432)

    Note over BatchJob,AuditVault: Subfeature 4.1 Execution Sequence: Balance Rollup, GL Recon & Reports

    %% STEP 1: GL TRIAL BALANCE & RECONCILIATION
    rect rgb(240, 248, 255)
    Note over BatchJob: Rule 1: Translate GL Recon command to Temenos OFS wire syntax
    BatchJob->>BatchJob: Map to OFS: GL.REPORT,GENERATE/I/PROCESS,,VALUE.DATE=20261005
    BatchJob->>CBS: POST /api/v1/internal/cbs/ofs-command (Payload: GL.REPORT,GENERATE wire string)
    CBS->>AzureSQL: SELECT gl_code, SUM(debit_amount) AS total_dr, SUM(credit_amount) AS total_cr FROM gl_ledger WHERE posting_date = '2026-10-05' GROUP BY gl_code
    AzureSQL-->>CBS: GL summary rows
    Note over CBS: Validate Zero-Sum Balance:<br/>Total Debits == Total Credits<br/>Variance == 0.0000 PHP
    alt Variance != 0 (Out-of-Balance Exception)
        CBS->>AzureSQL: INSERT INTO eod_reports_metadata (report_type: "GL_TRIAL_BALANCE", verification_status: "EXCEPTION", variance_amount: variance)
        CBS-->>BatchJob: 500 Internal Error (OFS: GL.REPORT//-1/FAILED,ERROR=OUT_OF_BALANCE)
        Note over BatchJob: Rule 3: Orchestrator records error into outbox prior to publishing
        BatchJob->>Kafka: Publish BatchErrorEvent (error: "GL Imbalance Detected", date: "2026-10-05")
    else Variance == 0 (Reconciliation Passed)
        CBS->>AzureSQL: INSERT INTO eod_reports_metadata (report_type: "GL_TRIAL_BALANCE", verification_status: "VERIFIED", variance_amount: 0.0000)
    end
    end

    %% STEP 2: DAILY TRANSACTION JOURNAL & SNAPSHOTS
    rect rgb(255, 250, 240)
    CBS->>AzureSQL: INSERT INTO eod_balance_snapshots (account_id, closing_balance, held_amount, snapshot_date) SELECT account_id, balance_amount, hold_amount, '2026-10-05' FROM balance_master
    AzureSQL-->>CBS: Snapshot records committed (e.g., 50,000 accounts frozen)

    CBS->>AzureSQL: SELECT * FROM transactions WHERE CAST(created_at AS DATE) = '2026-10-05' ORDER BY created_at ASC
    AzureSQL-->>CBS: Daily financial transactions list
    CBS->>Storage: Store Daily Transaction Journal (CSV/JSON/PDF)
    Storage-->>CBS: Storage URI: /vault/reports/20261005/txn_journal_20261005.pdf
    CBS->>AzureSQL: INSERT INTO eod_reports_metadata (report_type: "TXN_JOURNAL", storage_uri: "/vault/reports/...", verification_status: "VERIFIED")
    end

    %% STEP 3: AMLA CTR REGULATORY COMPLIANCE REPORT
    rect rgb(245, 255, 245)
    CBS->>AzureSQL: SELECT * FROM transactions WHERE amount >= 500000.0000 AND CAST(created_at AS DATE) = '2026-10-05'
    AzureSQL-->>CBS: High-value CTR records
    CBS->>Storage: Store AMLA CTR Compliance Report (JSON/XML)
    Storage-->>CBS: Storage URI: /vault/compliance/20261005/amla_ctr_20261005.json
    CBS->>AzureSQL: INSERT INTO eod_reports_metadata (report_type: "AMLA_CTR", storage_uri: "/vault/compliance/...", verification_status: "VERIFIED")
    end

    %% STEP 4: MONTHLY CUSTOMER E-STATEMENTS
    rect rgb(255, 245, 250)
    CBS->>AzureSQL: SELECT account_id, customer_id, email FROM accounts WHERE statement_cycle_day = 5 AND status = 'ACTIVE'
    AzureSQL-->>CBS: List of qualifying accounts (Cycle Day 5)
    loop For each statement-eligible account
        CBS->>AzureSQL: SELECT * FROM transactions WHERE account_id = ? AND created_at BETWEEN '2026-09-06' AND '2026-10-05'
        AzureSQL-->>CBS: Monthly transactions and opening/closing balances
    end
    CBS->>AzureSQL: INSERT INTO eod_reports_metadata (report_type: "EOD_BATCH_SUMMARY", verification_status: "COMPLETED")
    CBS-->>BatchJob: 200 OK (OFS: GL.REPORT//1/SUCCESS,BALANCED=YES,REPORT_COUNT=4,STATEMENT_COUNT=1250)
    end

    %% STEP 5: EVENT PUBLICATION & ASYNC CONSUMPTION
    rect rgb(240, 255, 255)
    Note over CBS,AzureSQL: Rule 3: Record reports completion and statement events into outbox_events
    CBS->>AzureSQL: INSERT INTO outbox_events (event_type: "REPORTS_READY", aggregate_id: "REP-20261005", status: "PENDING")
    CBS->>AzureSQL: INSERT INTO outbox_events (event_type: "STATEMENT_GENERATED", aggregate_id: "STMT-ACC-100223", status: "PENDING")

    Note over CBS,Kafka: Rule 3: CBS publishes reports & statement events directly to Kafka from outbox_events
    CBS->>Kafka: Publish ReportsReadyEvent (date: 2026-10-05, glBalanced: true, reportIds: ["GL_TRIAL_BAL", "TXN_JOURNAL", "AMLA_CTR", "EOD_SUMMARY"])
    CBS->>Kafka: Publish StatementGeneratedEvent (accountId: ACC-100223, cycleStart: 2026-09-06, cycleEnd: 2026-10-05)
    CBS->>AzureSQL: UPDATE outbox_events SET status = "PUBLISHED", published_at = SYSUTCDATETIME() WHERE aggregate_id IN ("REP-20261005", "STMT-ACC-100223") AND status = "PENDING"
    end

    par Downstream Statement Generation & Dispatch
        Kafka->>Notif: Consume StatementGeneratedEvent
        Notif->>Notif: Render HTML/PDF E-Statement via Thymeleaf
        Notif->>Notif: Dispatch E-Statement advice email via MailHog (:8025)
    and Rule 2: Append-Only Compliance Archival via Audit Worker
        Kafka->>AuditWorker: Consume ReportsReadyEvent
        AuditWorker->>AuditVault: INSERT INTO ledger_mutation_audit (event: "REPORTS_FILED", hash: sha256)
    end
```

---

## 4. Key Data Contracts & Schemas

### A. Database Table: `eod_reports_metadata` (Azure SQL)
```sql
CREATE TABLE eod_reports_metadata (
    report_id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    report_type         VARCHAR(64) NOT NULL, -- GL_TRIAL_BALANCE, TXN_JOURNAL, AMLA_CTR, EOD_SUMMARY
    business_date       DATE NOT NULL,
    generated_at        DATETIME2 DEFAULT SYSUTCDATETIME(),
    record_count        INT NOT NULL,
    total_debit_amount  DECIMAL(18, 4) NULL,
    total_credit_amount DECIMAL(18, 4) NULL,
    variance_amount     DECIMAL(18, 4) DEFAULT 0.0000,
    storage_uri         VARCHAR(512) NOT NULL,
    file_sha256_hash    CHAR(64) NOT NULL,
    verification_status VARCHAR(32) NOT NULL -- PENDING, VERIFIED, EXCEPTION
);
```

### B. Kafka Event: `StatementGeneratedEvent`
```json
{
  "eventId": "evt_stmt_992104",
  "eventType": "STATEMENT_GENERATED",
  "businessDate": "2026-10-05",
  "timestamp": "2026-10-05T23:59:45.120Z",
  "accountId": "ACC-100223",
  "customerId": "CUST-882190",
  "customerEmail": "customer@example.com",
  "currency": "PHP",
  "cycleStart": "2026-09-06",
  "cycleEnd": "2026-10-05",
  "openingBalance": 125000.0000,
  "closingBalance": 184500.5000,
  "totalDebits": 45000.0000,
  "totalCredits": 104500.5000,
  "interestCredited": 120.5000,
  "withholdingTaxWithheld": 24.1000,
  "transactionCount": 18
}
```

### C. Kafka Event: `ReportsReadyEvent`
```json
{
  "eventId": "evt_rep_771829",
  "eventType": "REPORTS_READY",
  "businessDate": "2026-10-05",
  "glBalanced": true,
  "totalLedgerDebits": 14500000.0000,
  "totalLedgerCredits": 14500000.0000,
  "generatedReports": [
    { "type": "GL_TRIAL_BALANCE", "records": 48, "status": "VERIFIED" },
    { "type": "TXN_JOURNAL", "records": 4210, "status": "STORED" },
    { "type": "AMLA_CTR", "records": 12, "status": "FILED" }
  ]
}
```
