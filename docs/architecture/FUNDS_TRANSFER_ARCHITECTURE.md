# Funds Transfer & Temenos OFS Settlement Architecture

This document defines the end-to-end technical architecture, swimlane process model, and sequence diagrams for the **Funds Transfer Workflow** in the revised Core Banking Platform architecture (`banking-platform-architecture.html`).

---

## 1. Architectural Overview & Component Responsibilities

Under the revised architecture, ledger mutations and balance locking have been centralized inside the **T24 Mock CBS**:

1. **Client Channels (`clients` :3000)**: React 18 & Flutter clients submit transfer requests with JWT authentication and `X-Idempotency-Key`.
2. **API Gateway (`gateway` :8080)**: Enforces perimeter authentication, checks JWT blacklists in **Redis (`redis` :6379)**, and applies token-bucket rate limits.
3. **Transfer Orchestrator (`orchestrator` :8082)**:
   - Validates payload syntax and acquires Redis idempotency locks.
   - Synchronously queries the **Python Risk Engine (`risk_engine` :8084)** (< 2ms SLA).
   - Coordinates step-up 2FA email verification via the **Notification Service (`notif_service` :8083)** for transfers exceeding 50,000.00 PHP or flagged by risk scoring.
   - Serializes validated JSON transfer requests into standard **Temenos Open Financial Services (OFS)** command syntax.
   - Dispatches OFS commands and hold requests to T24 Mock CBS over high-performance internal REST/TCP sockets.
   - **Proactive Observer & Kafka Event Broker**: Acts as the sole proxy for CBS events. Proactively checks / inspects CBS execution states and publishes all lifecycle domain events (`TransactionStatusChangedEvent`, `TransferExecutedEvent`, `TransferReversedEvent`) to Apache Kafka on behalf of the isolated CBS enclave.
4. **T24 Mock CBS (`t24_cbs` :8085)**:
   - Operates inside an **isolated core banking enclave**: it communicates **exclusively with Azure SQL Database and the Transfer Orchestrator**.
   - Holds the **exclusive primary datasource connection** to **Azure SQL Database (`azure_sql` :1433)**. No other microservice has direct datasource access.
   - Has **zero direct connection to Apache Kafka**, the Notification Service, or external networks.
   - If CBS state changes or requires external action, it never makes outbound calls; instead, the Transfer Orchestrator proactively queries CBS state or reads synchronous command returns and executes the outbound requests / event publications.
   - Ingests and parses Temenos OFS financial messages and internal REST hold/status instructions from the Orchestrator.
   - Acquires deterministic row-level locks (`SELECT ... WITH (UPDLOCK, ROWLOCK)`) to guarantee strict ACID concurrency and prevent race conditions or overdrafts.
   - Executes double-entry balance debits and credits, records transaction journals, and commits the ledger transaction.
   - Returns standard Temenos OFS success/error response strings (`FT26095A//1/SUCCESS`) and status responses directly to the Orchestrator.
5. **Event Emission & Downstream Consumers**:
   - The **Transfer Orchestrator** inspects CBS execution responses/states and publishes domain events (`TransactionStatusChangedEvent`, `TransferExecutedEvent`) to **Apache Kafka (`kafka` :9092)** on behalf of the CBS.
   - **Notification Service (`notif_service` :8083)** consumes Kafka events to generate and email HTML transaction receipts via MailHog.
   - **Azure PostgreSQL (`azure_pg` :5432)** serves as an append-only, tamper-proof **Audit Vault** (`ledger_mutation_audit`).

---

## 2. Funds Transfer Swimlane Diagram

```mermaid
flowchart TD
    %% ==========================================
    %% SWIMLANE: CLIENT CHANNELS
    %% ==========================================
    subgraph Lane_Client["Presentation Tier (Clients)"]
        CustomerApp["Customer Portal / Mobile App<br/>(React 18 & Flutter)"]
        OtpModal["Customer 2FA OTP Modal<br/>(Prompt for 6-Digit Email Code)"]
    end

    %% ==========================================
    %% SWIMLANE: EDGE PERIMETER & CACHE
    %% ==========================================
    subgraph Lane_Edge["Perimeter Security & In-Memory Cache Tier"]
        Gateway["API Gateway (:8080)<br/>Spring Cloud Gateway"]
        RedisCache[("Redis Cache (:6379)<br/>Tokens, Idempotency & OTP")]
    end

    %% ==========================================
    %% SWIMLANE: TRANSFER ORCHESTRATOR
    %% ==========================================
    subgraph Lane_Orch["Transfer Orchestrator (:8082) - Event Broker & Flow Controller"]
        IdempCheck["1. Idempotency & Schema Validation<br/>(Check X-Idempotency-Key)"]
        RiskCoordinator["2. Risk Screening Coordinator<br/>(Sync Call to Python Engine)"]
        TwoFaCoordinator{"3. 2FA Threshold Triage<br/>(Amount > 50k or Risk Escalated?)"}
        OtpValidator["3b. Verify 2FA OTP Code<br/>(Consume token from Redis)"]
        HoldRequester["4. Instruct CBS to Apply Hold<br/>(Transition to RESERVED)"]
        OfsSerializer["5. Temenos OFS Serializer<br/>(JSON -> FUNDS.TRANSFER...)"]
        EventProducer["6. Publish Lifecycle Events to Kafka<br/>(Brokered on behalf of CBS)"]
        ReceiptHandler["7. Evict Cache & Return 200 OK<br/>(Transaction Receipt)"]
    end

    %% ==========================================
    %% SWIMLANE: FRAUD RISK ENGINE
    %% ==========================================
    subgraph Lane_Risk["Decoupled Fraud Risk Engine (:8084)"]
        XgbModel["Python FastAPI + XGBoost<br/>Real-Time Scoring (< 2ms SLA)<br/>Verdict: ALLOW / 2FA / BLOCK"]
    end

    %% ==========================================
    %% SWIMLANE: T24 MOCK CBS (ISOLATED CORE ENCLAVE)
    %% ==========================================
    subgraph Lane_CBS["T24 Mock CBS (:8085) - Isolated Core Banking Enclave"]
        HoldKernel["Hold Management Kernel<br/>(UPDLOCK on balance_master)"]
        OfsParser["OFS Parser & State Machine<br/>(Verify Account States & Dates)"]
        ConcurrencyKernel["ACID Concurrency Kernel<br/>(UPDLOCK Deterministic Ordering)"]
        LedgerMutation["Double-Entry Mutation<br/>(Debit Source, Credit Target)"]
    end

    %% ==========================================
    %% SWIMLANE: AZURE SQL MASTER STORAGE
    %% ==========================================
    subgraph Lane_SQL["Azure SQL Database (:1433) - Master Ledgers"]
        BalanceMaster[("balance_master<br/>(Row-Level UPDLOCK, ROWLOCK)")]
        TransactionsTable[("transactions<br/>(Financial Journals)")]
        GlLedgerTable[("gl_ledger<br/>(Double-Entry Accounts)")]
    end

    %% ==========================================
    %% SWIMLANE: EVENT STREAM & AUDIT
    %% ==========================================
    subgraph Lane_Downstream["Event Stream, Alerts & Compliance Vault"]
        KafkaBroker["Apache Kafka (:9092)<br/>Topic: banking.transfers.events"]
        NotifService["Notification Service (:8083)<br/>(HTML Receipts via MailHog :8025)"]
        AuditVault[("Azure PostgreSQL (:5432)<br/>Immutable Audit Vault")]
    end

    %% PROCESS FLOW CONNECTIONS
    CustomerApp -->|"POST /api/v1/transfers (JWT)"| Gateway
    Gateway -->|"Token Blacklist Check"| RedisCache
    Gateway -->|"Route Validated Request"| IdempCheck

    IdempCheck -->|"Check & Set Idempotency Lock"| RedisCache
    IdempCheck --> RiskCoordinator

    RiskCoordinator -->|"POST /api/v1/risk/transfer"| XgbModel
    XgbModel -->|"Score <= 0.15 (ALLOW)"| TwoFaCoordinator
    XgbModel -.->|"Score > 0.85 (BLOCK)"| RejectRisk["403 Forbidden: High Risk"]

    TwoFaCoordinator -->|"No: Amount <= 50,000 (STP Fast Path)"| HoldRequester
    TwoFaCoordinator -->|"Yes: Amount > 50,000 PHP"| GenOtp["Generate 6-Digit OTP (TTL 300s)"]

    GenOtp -->|"Store OTP in Redis"| RedisCache
    GenOtp -->|"Send OTP Email Advice"| NotifService
    GenOtp -->|"202 Accepted: PENDING_2FA"| CustomerApp

    CustomerApp -->|"Submit OTP Code"| OtpModal
    OtpModal -->|"POST /transfers/verify-otp"| OtpValidator
    OtpValidator -->|"Validate & DEL OTP"| RedisCache
    OtpValidator --> HoldRequester

    HoldRequester -->|"POST /internal/cbs/holds/apply"| HoldKernel
    HoldKernel -->|"UPDATE hold_amount"| BalanceMaster
    HoldKernel -->|"200 OK (Hold Applied)"| OfsSerializer

    OfsSerializer -->|"Dispatch OFS Financial String"| OfsParser
    OfsParser --> ConcurrencyKernel

    ConcurrencyKernel -->|"SELECT WITH (UPDLOCK, ROWLOCK)"| BalanceMaster
    ConcurrencyKernel --> LedgerMutation

    LedgerMutation -->|"Atomic Balance & Hold Updates"| BalanceMaster
    LedgerMutation -->|"Insert Txn Record"| TransactionsTable
    LedgerMutation -->|"Insert GL Double-Entry"| GlLedgerTable
    LedgerMutation -->|"OFS Return: FT26095A//1/SUCCESS"| EventProducer

    EventProducer -->|"Publish Events (POSTED, TransferExecuted)"| KafkaBroker
    EventProducer --> ReceiptHandler

    ReceiptHandler -->|"Evict Balance Cache (DEL)"| RedisCache
    ReceiptHandler -->|"200 OK + Transaction Receipt"| CustomerApp

    KafkaBroker -->|"Consume Transfer Event"| NotifService
    KafkaBroker -->|"Append-Only Audit Log"| AuditVault
```

---

## 3. Funds Transfer Sequence Diagram (Request / Response Flow)

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Customer (Web / Mobile)
    participant Gateway as API Gateway (:8080)
    participant Redis as Redis Cache (:6379)
    participant Orch as Transfer Orchestrator (:8082)
    participant RiskEngine as Risk Engine (:8084)
    participant CBS as T24 Mock CBS (:8085)
    participant AzureSQL as Azure SQL DB (:1433)
    participant Kafka as Kafka Broker (:9092)
    participant Notif as Notification Svc (:8083)
    participant AuditVault as Postgres Audit (:5432)

    %% STAGE 1: INGESTION & PERIMETER (INITIATED)
    rect rgb(240, 248, 255)
    Note over Customer,CBS: Stage 1: Ingestion & CBS State Creation (INITIATED)
    Customer->>Gateway: POST /api/v1/transfers (Bearer JWT, Key: TX-901, Amount: 65000.00 PHP)
    Gateway->>Redis: GET blacklist:jti:<jti> (Sub-5ms token check)
    Redis-->>Gateway: Null (Token is valid and active)
    Gateway->>Orch: Forward transfer request
    Orch->>Redis: SET tx:idemp:TX-901 "PROCESSING" NX EX 60
    Redis-->>Orch: OK (Lock acquired - duplicate prevention active)
    Note over Orch,CBS: Orchestrator instructs isolated T24 CBS to create initial transaction state
    Orch->>CBS: POST /api/v1/internal/cbs/transfers/initiate (TX-901, ACC-101, ACC-202, 65000.00 PHP)
    CBS->>AzureSQL: INSERT INTO transactions (id: "TX-901", status: "INITIATED")
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-901", to_status: "INITIATED", reason: "API_INGESTION")
    CBS-->>Orch: 201 Created (TX-901 INITIATED)
    Note over Orch,Kafka: Orchestrator publishes event to Kafka on behalf of isolated CBS
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-901, INITIATED)
    end

    %% STAGE 2: REAL-TIME RISK EVALUATION & 2FA (AUTHORIZED)
    rect rgb(255, 250, 240)
    Note over Orch,RiskEngine: Stage 2: Risk Screening & Step-Up 2FA (AUTHORIZED)
    Orch->>RiskEngine: POST /api/v1/risk/transfer (ACC-101, Amount: 65000.00 PHP)
    RiskEngine-->>Orch: 200 OK (verdict: ALLOW, riskScore: 0.12, latencyMs: 1.4)
    RiskEngine->>Kafka: Publish RiskEvaluatedEvent (Asynchronous audit telemetry)

    Note over Orch: Amount 65,000.00 PHP exceeds 50,000.00 PHP threshold (Mandate 2FA OTP)
    Orch->>Redis: SET otp:TX-901 "491823" EX 300 (5-minute TTL)
    Orch->>Kafka: Publish OtpDispatchRequestedEvent (TX-901, cust@bank.com)
    Kafka->>Notif: Consume event & send 6-digit OTP email via MailHog (:8025)
    Orch-->>Gateway: HTTP 202 Accepted (TX-901, PENDING_2FA_VERIFICATION)
    Gateway-->>Customer: HTTP 202 Accepted (Display 2FA OTP Input Screen)

    Customer->>Gateway: POST /api/v1/transfers/TX-901/verify-otp (OTP: 491823)
    Gateway->>Orch: Forward OTP verification
    Orch->>Redis: GET otp:TX-901
    Redis-->>Orch: "491823" (Match confirmed)
    Orch->>Redis: DEL otp:TX-901 (Atomic token consumption)

    Note over Orch,CBS: Orchestrator instructs T24 CBS: 2FA & Risk passed
    Orch->>CBS: POST /api/v1/internal/cbs/transfers/TX-901/authorize
    CBS->>AzureSQL: UPDATE transactions SET status = "AUTHORIZED" WHERE id = "TX-901"
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-901", from_status: "INITIATED", to_status: "AUTHORIZED", reason: "RISK_AND_2FA_PASSED")
    CBS-->>Orch: 200 OK (TX-901 AUTHORIZED)
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-901, AUTHORIZED)
    end

    %% STAGE 3: AMOUNT HOLD PLACEMENT (RESERVED)
    rect rgb(255, 245, 250)
    Note over Orch,AzureSQL: Stage 3: Pre-Settlement Amount Hold (RESERVED)
    Orch->>CBS: POST /api/v1/internal/cbs/transfers/TX-901/reserve (ACC-101, Amount: 65000.00 PHP)
    CBS->>AzureSQL: BEGIN TRANSACTION
    CBS->>AzureSQL: SELECT balance_amount, hold_amount FROM balance_master WITH (UPDLOCK, ROWLOCK) WHERE account_id = 'ACC-101'
    AzureSQL-->>CBS: balance_amount = 100000.00, hold_amount = 0.00 (Available: 100000.00 >= 65000.00)
    CBS->>AzureSQL: UPDATE balance_master SET hold_amount = hold_amount + 65000.0000 WHERE account_id = 'ACC-101'
    CBS->>AzureSQL: UPDATE transactions SET status = "RESERVED" WHERE id = "TX-901"
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-901", from_status: "AUTHORIZED", to_status: "RESERVED", reason: "AMOUNT_HOLD_APPLIED")
    CBS->>AzureSQL: COMMIT TRANSACTION
    CBS-->>Orch: 200 OK (Hold applied, Available Balance: 35000.00 PHP)
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-901, RESERVED)
    end

    %% STAGE 4: TEMENOS OFS DISPATCH & IN-FLIGHT STATE (PROCESSING)
    rect rgb(245, 255, 245)
    Note over Orch,CBS: Stage 4: OFS Serialization & In-Flight Transition (PROCESSING)
    Note over Orch: Serialize JSON to Temenos OFS format (FUNDS.TRANSFER,AUTH/I/PROCESS...)
    Orch->>CBS: POST /api/v1/internal/cbs/ofs-command (Payload: OFS Financial String)
    CBS->>AzureSQL: UPDATE transactions SET status = "PROCESSING" WHERE id = "TX-901"
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-901", from_status: "RESERVED", to_status: "PROCESSING", reason: "T24_OFS_PROCESSING")
    Note over CBS,AzureSQL: CBS performs ACID ledger settlement within internal transaction
    end

    %% STAGE 5: T24 MOCK CBS ACID SETTLEMENT & HOLD RELEASE (POSTED)
    rect rgb(240, 255, 255)
    Note over CBS,AzureSQL: Stage 5: ACID Ledger Settlement & Release Hold (POSTED)
    CBS->>AzureSQL: BEGIN TRANSACTION
    CBS->>AzureSQL: SELECT account_id, balance_amount, hold_amount FROM balance_master WITH (UPDLOCK, ROWLOCK) WHERE account_id IN ('ACC-101', 'ACC-202') ORDER BY account_id ASC
    AzureSQL-->>CBS: Locks acquired (ACC-101 Available: 35000.00 PHP, ACC-202 Available: 15000.00 PHP)
    Note over CBS: Deduct Balance & Release Hold Simultaneously
    CBS->>AzureSQL: UPDATE balance_master SET balance_amount = balance_amount - 65000.0000, hold_amount = hold_amount - 65000.0000 WHERE account_id = 'ACC-101'
    CBS->>AzureSQL: UPDATE balance_master SET balance_amount = balance_amount + 65000.0000 WHERE account_id = 'ACC-202'
    CBS->>AzureSQL: INSERT INTO transactions (id: "FT26095A", type: "TRANSFER", amount: 65000.0000, status: "POSTED")
    CBS->>AzureSQL: INSERT INTO gl_ledger (DR: ACC-101, CR: ACC-202, amount: 65000.0000, ref: "FT26095A")
    CBS->>AzureSQL: UPDATE transactions SET status = "POSTED" WHERE id = "TX-901"
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-901", from_status: "PROCESSING", to_status: "POSTED", reason: "LEDGER_COMMITTED")
    CBS->>AzureSQL: COMMIT TRANSACTION
    AzureSQL-->>CBS: Transaction Committed (ACID Complete)
    CBS-->>Orch: OFS Response: FT26095A//1/SUCCESS,AUTH.DATE=20261005,STATUS=POSTED
    
    Note over Orch: Proactive State Verification & Kafka Brokerage
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-901, PROCESSING)
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-901, POSTED)
    Orch->>Kafka: Publish TransferExecutedEvent (txId: FT26095A, amount: 65000.00 PHP)
    end

    %% STAGE 6: RECEIPT DELIVERY & ASYNC CONSUMERS
    rect rgb(250, 250, 250)
    Note over Orch,AuditVault: Stage 6: Receipt Delivery & Asynchronous Fan-Out
    Orch->>Redis: DEL account:balance:ACC-101 account:balance:ACC-202 (Evict cached balances)
    Orch-->>Gateway: HTTP 200 OK (transferId: TX-901, cbsRef: FT26095A, status: POSTED, amount: 65000.00 PHP)
    Gateway-->>Customer: HTTP 200 OK (Transfer Success Screen & Receipt)

    par Asynchronous Receipt Email Delivery
        Kafka->>Notif: Consume TransferExecutedEvent
        Notif->>Notif: Render HTML customer receipt via Thymeleaf
        Notif->>Notif: Dispatch email to sender & recipient via MailHog (:8025)
    and Asynchronous Immutable Compliance Projection
        Kafka->>AuditVault: Consume TransactionStatusChangedEvents & TransferExecutedEvent
        AuditVault->>AuditVault: INSERT INTO ledger_mutation_audit (Full Status Roll & Ledger Postings)
    end
    end
```

---

## 4. Alternate Flows & Exception Handling

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Customer
    actor Maker as Maker (Dispute Teller)
    actor Checker as Checker (Branch Manager)
    participant Gateway as API Gateway
    participant Orch as Orchestrator
    participant Risk as Risk Engine
    participant CBS as T24 Mock CBS
    participant AzureSQL as Azure SQL DB
    participant Kafka as Kafka Broker

    %% EXCEPTION 1: HIGH RISK FRAUD SCORE
    rect rgb(255, 235, 235)
    Note over Customer,Risk: Scenario A: High Risk Fraud Score (> 0.85)
    Customer->>Gateway: POST /transfers (Amount: 200000.00 PHP, suspicious geolocation)
    Gateway->>Orch: Forward transfer request
    Orch->>Risk: POST /api/v1/risk/transfer
    Risk-->>Orch: 200 OK (verdict: BLOCK, riskScore: 0.94, reason: IMPOSSIBLE_TRAVEL)
    Note over Orch: Risk Score exceeds 0.85 threshold (Abort before CBS)
    Orch-->>Customer: HTTP 403 Forbidden (RISK_SCORE_BLOCKED)
    end

    %% EXCEPTION 2: INSUFFICIENT FUNDS
    rect rgb(255, 240, 235)
    Note over Customer,AzureSQL: Scenario B: Insufficient Funds in Azure SQL (FAILED State)
    Customer->>Gateway: POST /transfers (TX-902, Amount: 100000.00 PHP)
    Gateway->>Orch: Forward transfer request
    Orch->>CBS: POST /api/v1/internal/cbs/transfers/TX-902/reserve (ACC-101, 100000.00 PHP)
    CBS->>AzureSQL: BEGIN TX & SELECT WITH (UPDLOCK, ROWLOCK) WHERE account_id = 'ACC-101'
    AzureSQL-->>CBS: balance_amount = 45000.0000, hold_amount = 0.0000
    Note over CBS: Available 45000.00 < 100000.00 (Insufficient Funds)
    CBS->>AzureSQL: UPDATE transactions SET status = "FAILED" WHERE id = 'TX-902'
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-902", to_status: "FAILED", reason: "NSF_INSUFFICIENT_FUNDS")
    CBS->>AzureSQL: COMMIT TRANSACTION
    CBS-->>Orch: HTTP 422 Unprocessable Entity (NSF_INSUFFICIENT_FUNDS)
    Note over Orch,Kafka: Orchestrator publishes FAILED event to Kafka
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-902, FAILED, reason: "NSF")
    Orch-->>Customer: HTTP 422 Unprocessable Entity (INSUFFICIENT_FUNDS)
    end

    %% SCENARIO C: PROACTIVE STATE VERIFICATION (POLLING PATTERN)
    rect rgb(245, 255, 245)
    Note over Orch,CBS: Scenario C: Proactive Polling for Asynchronous / In-Flight State
    Note over Orch: In-flight or timeout recovery: Orchestrator proactively polls CBS
    Orch->>CBS: GET /api/v1/internal/cbs/transfers/TX-901/status
    CBS->>AzureSQL: SELECT status, reversal_ref_id FROM transactions WHERE id = 'TX-901'
    AzureSQL-->>CBS: status = "POSTED"
    CBS-->>Orch: 200 OK { id: "TX-901", status: "POSTED", cbsRef: "FT26095A" }
    Note over Orch: Orchestrator verifies terminal state and publishes missing events if needed
    end

    %% SCENARIO D: INTRA-BANK MAKER-CHECKER REVERSAL (DUAL CONTROL GOVERNANCE)
    rect rgb(255, 245, 255)
    Note over Maker,Kafka: Scenario D: Intra-Bank Reversal (Maker-Checker Dual Control & Beneficiary Lien)
    Maker->>Gateway: POST /api/v1/transfers/TX-901/reversal-request { disputeTicket: "DISP-8801", reason: "DUPLICATE_TRANSFER", makerId: "OP-MAKER-01" }
    Gateway->>Orch: Forward with Maker credentials
    Note over Orch,CBS: Orchestrator instructs CBS to validate intra-bank status and place beneficiary lien
    Orch->>CBS: POST /api/v1/internal/cbs/transfers/TX-901/reversal-request { ticketId: "DISP-8801", makerId: "OP-MAKER-01" }
    CBS->>AzureSQL: BEGIN TRANSACTION
    CBS->>AzureSQL: SELECT source_account_id, target_account_id, status, amount FROM transactions WITH (UPDLOCK) WHERE id = 'TX-901'
    Note over CBS: Validate Intra-Bank: Both ACC-101 and ACC-202 exist in CBS and status is POSTED
    CBS->>AzureSQL: SELECT balance_amount, hold_amount FROM balance_master WITH (UPDLOCK, ROWLOCK) WHERE account_id = 'ACC-202'
    Note over CBS: Place Pre-Reversal Lien on Beneficiary (Freeze 65000.00 PHP)
    CBS->>AzureSQL: UPDATE balance_master SET hold_amount = hold_amount + 65000.00 WHERE account_id = 'ACC-202'
    CBS->>AzureSQL: INSERT INTO reversal_requests (ticket_id, transaction_id, maker_id, reversal_reason, status) VALUES ('DISP-8801', 'TX-901', 'OP-MAKER-01', 'DUPLICATE_TRANSFER', 'PENDING_APPROVAL')
    CBS->>AzureSQL: UPDATE transactions SET status = "REVERSAL_REQUESTED" WHERE id = 'TX-901'
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-901", from_status: "POSTED", to_status: "REVERSAL_REQUESTED", reason: "MAKER_DISPUTE_FILED", operator_id: "OP-MAKER-01")
    CBS->>AzureSQL: COMMIT TRANSACTION
    CBS-->>Orch: 201 Created { ticketId: "DISP-8801", status: "PENDING_APPROVAL" }
    Note over Orch,Kafka: Orchestrator publishes event to Kafka on behalf of isolated CBS
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-901, REVERSAL_REQUESTED, ticketId: "DISP-8801")
    Orch-->>Maker: 201 Created (Reversal Request Queued for Checker Review)

    Note over Checker,Orch: Checker reviews reversal ticket in authorization queue
    Checker->>Gateway: POST /api/v1/transfers/TX-901/reversal-request/DISP-8801/review { decision: "APPROVE", checkerId: "OP-CHECKER-09" }
    Gateway->>Orch: Forward with Checker credentials
    Note over Orch: Enforce Segregation of Duties: checkerId != makerId (OP-CHECKER-09 != OP-MAKER-01)
    Orch->>CBS: POST /api/v1/internal/cbs/transfers/TX-901/reversal-request/approve { checkerId: "OP-CHECKER-09", ticketId: "DISP-8801" }
    CBS->>AzureSQL: BEGIN TRANSACTION
    CBS->>AzureSQL: SELECT status FROM reversal_requests WITH (UPDLOCK) WHERE ticket_id = 'DISP-8801'
    Note over CBS: Execute Compensating Double-Entry & Release Beneficiary Lien
    CBS->>AzureSQL: UPDATE balance_master SET balance_amount = balance_amount - 65000.00, hold_amount = hold_amount - 65000.00 WHERE account_id = 'ACC-202'
    CBS->>AzureSQL: UPDATE balance_master SET balance_amount = balance_amount + 65000.00 WHERE account_id = 'ACC-101'
    CBS->>AzureSQL: INSERT INTO transactions (id: "TX-REV-901", type: "REVERSAL", amount: 65000.00, status: "POSTED", original_tx_id: "TX-901")
    CBS->>AzureSQL: INSERT INTO gl_ledger (DR: ACC-202, CR: ACC-101, amount: 65000.00, ref: "TX-REV-901")
    CBS->>AzureSQL: UPDATE reversal_requests SET status = "APPROVED", checker_id = "OP-CHECKER-09", reviewed_at = SYSUTCDATETIME() WHERE ticket_id = 'DISP-8801'
    CBS->>AzureSQL: UPDATE transactions SET status = "REVERSED", reversal_ref_id = "TX-REV-901" WHERE id = 'TX-901'
    CBS->>AzureSQL: INSERT INTO transaction_status_history (tx_id: "TX-901", from_status: "REVERSAL_REQUESTED", to_status: "REVERSED", reason: "CHECKER_APPROVED", operator_id: "OP-CHECKER-09")
    CBS->>AzureSQL: COMMIT TRANSACTION
    CBS-->>Orch: 200 OK { id: "TX-901", status: "REVERSED", reversalTxId: "TX-REV-901" }
    Note over Orch,Kafka: Orchestrator publishes REVERSED events to Kafka
    Orch->>Kafka: Publish TransactionStatusChangedEvent (TX-901, REVERSED, ref: "TX-REV-901")
    Orch->>Kafka: Publish TransferReversedEvent (txId: "TX-901", reversalRefId: "TX-REV-901", makerId: "OP-MAKER-01", checkerId: "OP-CHECKER-09")
    Orch-->>Checker: HTTP 200 OK (Transfer Reversal Confirmed)
    end
```

---

## 5. Key Financial Data Contracts & Schemas

### A. Temenos OFS Request Message Format
Sent from Transfer Orchestrator (`:8082`) to T24 Mock CBS (`:8085`):
```text
FUNDS.TRANSFER,AUTH/I/PROCESS,//PH100223,
TXN.REF=FT26095A,
DEBIT.ACCT.NO=ACC-100223,
CREDIT.ACCT.NO=ACC-200456,
DEBIT.AMOUNT=65000.0000,
DEBIT.CURRENCY=PHP,
PAYMENT.DETAILS=Invoice #12,
ORDERING.CUST=CUST-882190
```

### B. Temenos OFS Success Response Format
Returned from T24 Mock CBS (`:8085`) to Transfer Orchestrator (`:8082`):
```text
FT26095A//1/SUCCESS,
AUTH.DATE=20261005,
TIME=15:40:02,
STATUS=POSTED,
DEBIT.ACCT=ACC-100223,
CREDIT.ACCT=ACC-200456,
POSTED.AMOUNT=65000.0000
```

### C. Kafka Event Payload: `TransferExecutedEvent`
Emitted by Transfer Orchestrator (`:8082`) on behalf of T24 Mock CBS to topic `banking.transfers.events`:
```json
{
  "eventId": "evt_tx_551982",
  "eventType": "TRANSFER_EXECUTED",
  "transferId": "FT26095A",
  "businessDate": "2026-10-05",
  "timestamp": "2026-10-05T15:40:02.145Z",
  "sourceAccountId": "ACC-100223",
  "targetAccountId": "ACC-200456",
  "amount": 65000.0000,
  "currency": "PHP",
  "feeAmount": 0.0000,
  "status": "POSTED",
  "riskScore": 0.12,
  "authMethod": "2FA_EMAIL_OTP",
  "sourceNewBalance": 35000.0000,
  "targetNewBalance": 80000.0000,
  "referenceHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

### D. Kafka Event Payload: `RiskEvaluatedEvent` & Downstream Consumers
Emitted by the Python Risk Engine to topic `banking.risk.evaluations`:
```json
{
  "eventId": "evt_risk_109281",
  "eventType": "RISK_EVALUATED",
  "transferId": "TX-901",
  "timestamp": "2026-10-05T15:40:01.420Z",
  "sourceAccountId": "ACC-100223",
  "amount": 65000.0000,
  "riskScore": 0.12,
  "verdict": "ALLOW",
  "modelId": "xgboost_v2_fastpath",
  "inferenceLatencyMs": 1.4,
  "extractedFeatures": {
    "velocity10m": 1,
    "ipReputationScore": 0.02,
    "deviceKnown": true,
    "unusualHours": false
  }
}
```

#### Downstream Consumers of `RiskEvaluatedEvent`:
1. **Azure PostgreSQL Audit Vault (`azure_pg` :5432)**:
   - Consumer group `audit-vault-workers` projects the event into `risk_decision_audit`.
   - **Regulatory Purpose (BSP Circular 808 & AMLA)**: Proves to auditors why an automated ML algorithm approved, challenged, or blocked a financial transaction.
2. **Asynchronous "Second-Look" Reviewer (NanoJev / Qwen2.5-0.5B ONNX)**:
   - As documented in [`decoupled_risk_flow.html`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/decoupled_risk_flow.html), background worker pools consume the event to perform deep semantic evaluation of transfer memos and scam typologies without delaying the sub-2ms synchronous fast path.
3. **Compliance Analyst Queue (`analyst_portal`)**:
   - Borderline scores (e.g., 0.60 to 0.85) or transactions flagged with anomalous travel speeds populate the Compliance Officer Dashboard for manual review and Suspicious Transaction Report (STR) filings.
4. **Model Drift & Observability Telemetry (Datadog APM)**:
   - Tracks prediction distribution drift, feature attribution anomalies, and model latency percentiles (p50/p95/p99) over time.
