# Core Banking Capabilities: Technical Architecture, Dedicated History Schemas & Sequence Diagrams

This document defines the complete technical specifications and network request sequence diagrams for the three assigned core banking features:

1. **Transaction Reversal** (Chargebacks, Operational Corrections, and Dispute Resolution)
2. **Amount Holds / Reservations** (Provisional Locking, Maker-Checker Holds, and Pre-Authorizations via `AC.LOCKED.EVENTS`)
3. **Failed Transaction / Retry Management** (Idempotent Interrogation, Circuit Breaker, and Dead Letter Queue)

---

## 1. Architectural System Overview

The architecture enforces a strict boundary between Edge Orchestration and the Authoritative Core Banking System (CBS):

```
┌─────────────────┐       HTTPS / JWT       ┌─────────────────┐    token check    ┌─────────────────┐
│ Client Channels │────────────────────────►│   API Gateway   │──────────────────►│   Token Cache   │
│ React & Flutter │                         │  Spring Cloud   │                   │   Redis :6379   │
└─────────────────┘                         └────────┬────────┘                   └─────────────────┘
                                                     │ route request
                                                     ▼
                                            ┌───────────────────────┐
                                            │ Transfer Orchestrator │◄───sync risk check───►┌────────────────┐
                                            │   Spring Boot :8082   │                       │  Risk Engine   │
                                            └──────────┬────────────┘                       │ Python :8084   │
                                                       │                                    └───────┬────────┘
                    ┌──────────────────────────────────┼────────────────────────────────┐           │
                    │ 2FA OTP (if 2FA)                 │ JSON to OFS Wire               │           │ risk evals
                    ▼                                  ▼                                │           ▼
         ┌──────────────────────┐          ┌───────────────────────┐                    │   ┌───────────────┐
         │ Notification Service │          │     T24 Mock CBS      │──transfer events───┼──►│ Event Stream  │
         │  Spring Boot :8083   │          │   Spring Boot :8085   │                    │   │  Kafka :9092  │
         └──────────────────────┘          └───────────┬───────────┘                    │   └───────┬───────┘
                                                       │ ACID balance updates           │           │
                                                       ▼                                │           │ audit proj
                                            ┌───────────────────────┐                   │           ▼
                                            │  Azure SQL Database   │                   │   ┌───────────────┐
                                            │   Master CBS Ledgers  │                   └──►│  Audit Vault  │
                                            │ (Exclusive Connection)│                       │PostgreSQL:5432│
                                            └───────────────────────┘                       └───────────────┘
```

### Architectural Invariants

1. **Temenos OFS Contract:** Transfer Orchestrator translates REST JSON payloads into official Temenos OFS (Open Financial Service) syntax strings before dispatching to T24 CBS.
2. **Exclusive Primary Ledger Connection:** Only **T24 Mock CBS** has direct database connection to the Master CBS Ledgers in Azure SQL.
3. **Dedicated History Tracking:** Each feature maintains its own dedicated, isolated history table in Azure SQL rather than dumping records into a single generic table.

---

## 2. Dedicated Feature History Tables (Database Schema)

Per core banking regulatory standards, each feature has its own dedicated audit and history table with specialized columns:

```mermaid
erDiagram
    TRANSACTION_REVERSAL_HISTORY {
        string reversal_id PK
        string original_transaction_id FK
        string source_account_id
        string target_account_id
        decimal reversal_amount
        string currency
        string reversal_reason
        string reversal_status
        string failure_reason
        string initiated_by
        string approved_by
        datetime created_at
        datetime settled_at
    }

    AMOUNT_HOLD_HISTORY {
        string hold_id PK
        string account_id
        decimal hold_amount
        string currency
        string hold_reason
        string hold_status
        string reference_txn_id
        datetime expiry_date
        string initiated_by
        string checker_id
        datetime created_at
        datetime updated_at
    }

    FAILED_TRANSACTION_HISTORY {
        string failure_id PK
        string idempotency_key
        string source_account_id
        string target_account_id
        decimal attempted_amount
        string failure_stage
        string error_code
        string error_message
        int retry_count
        string resolution_status
        string dlq_topic
        datetime created_at
        datetime last_attempt_at
    }
```

### 2.1 Table 1: `transaction_reversal_history`

Tracks the exact lifecycle of every reversal request, including approvals, clawback executions, and reasons for failure:

| Column Name               | Data Type       | Nullable | Description                                                                                                                                              |
| :------------------------ | :-------------- | :------- | :------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `reversal_id`             | `VARCHAR(64)`   | `NO`     | **Primary Key** (e.g. `REV-20261005-001`). Unique identifier for this reversal action.                                                                   |
| `original_transaction_id` | `VARCHAR(64)`   | `NO`     | Foreign Key to original transaction (`transaction_master.txn_id`).                                                                                       |
| `source_account_id`       | `VARCHAR(32)`   | `NO`     | Original sender account receiving funds back.                                                                                                            |
| `target_account_id`       | `VARCHAR(32)`   | `NO`     | Original recipient account from which funds are clawed back.                                                                                             |
| `reversal_amount`         | `DECIMAL(18,2)` | `NO`     | The amount being clawed back and reversed.                                                                                                               |
| `currency`                | `VARCHAR(3)`    | `NO`     | Currency code (`PHP`).                                                                                                                                   |
| `reversal_reason`         | `VARCHAR(255)`  | `NO`     | **Required column** explaining why the transaction was reversed (e.g., `OPERATIONAL_ERROR`, `CUSTOMER_DISPUTE`, `FRAUD_CLAWBACK`, `DUPLICATE_TRANSFER`). |
| `reversal_status`         | `VARCHAR(32)`   | `NO`     | Current status: `PENDING_APPROVAL`, `APPROVED`, **`REVERSED`**, `FAILED`, `REJECTED`.                                                                    |
| `failure_reason`          | `VARCHAR(255)`  | `YES`    | Explicit reason if clawback failed (e.g., `INSUFFICIENT_FUNDS_FOR_CLAWBACK`, `ACCOUNT_CLOSED`, `LEGAL_FREEZE`).                                          |
| `initiated_by`            | `VARCHAR(64)`   | `NO`     | User / Teller ID who initiated the reversal.                                                                                                             |
| `approved_by`             | `VARCHAR(64)`   | `YES`    | Supervisor / Checker ID who approved the reversal.                                                                                                       |
| `created_at`              | `DATETIME2`     | `NO`     | Timestamp when reversal was initiated.                                                                                                                   |
| `settled_at`              | `DATETIME2`     | `YES`    | Timestamp when ledger mutation completed and status changed to `REVERSED`.                                                                               |

---

### 2.2 Table 2: `amount_hold_history`

Tracks the lifecycle of provisional locks and pre-authorizations (`AC.LOCKED.EVENTS`):

| Column Name        | Data Type       | Nullable | Description                                                                                |
| :----------------- | :-------------- | :------- | :----------------------------------------------------------------------------------------- |
| `hold_id`          | `VARCHAR(64)`   | `NO`     | **Primary Key** (e.g. `HLD-20261005-001`). Unique hold identifier.                         |
| `account_id`       | `VARCHAR(32)`   | `NO`     | Account on which the funds are locked.                                                     |
| `hold_amount`      | `DECIMAL(18,2)` | `NO`     | Amount locked from available balance.                                                      |
| `currency`         | `VARCHAR(3)`    | `NO`     | Currency code (`PHP`).                                                                     |
| `hold_reason`      | `VARCHAR(255)`  | `NO`     | Reason for hold (e.g., `MAKER_CHECKER_PENDING`, `CARD_PRE_AUTH`, `DISPUTE_INVESTIGATION`). |
| `hold_status`      | `VARCHAR(32)`   | `NO`     | Status: `ACTIVE`, `CAPTURED`, `RELEASED`, `EXPIRED`, `FAILED`.                             |
| `reference_txn_id` | `VARCHAR(64)`   | `YES`    | Final settlement transaction ID if hold was captured into a settled transfer.              |
| `expiry_date`      | `DATETIME2`     | `NO`     | Automatic expiry threshold.                                                                |
| `initiated_by`     | `VARCHAR(64)`   | `NO`     | Originating channel / user.                                                                |
| `checker_id`       | `VARCHAR(64)`   | `YES`    | Supervisor who approved capture or release.                                                |
| `created_at`       | `DATETIME2`     | `NO`     | Hold placement timestamp.                                                                  |
| `updated_at`       | `DATETIME2`     | `NO`     | Hold release or capture timestamp.                                                         |

---

### 2.3 Table 3: `failed_transaction_history`

Tracks network drops, gateway timeouts, idempotency interrogation outcomes, and DLQ escalations:

| Column Name         | Data Type       | Nullable | Description                                                                                                               |
| :------------------ | :-------------- | :------- | :------------------------------------------------------------------------------------------------------------------------ |
| `failure_id`        | `VARCHAR(64)`   | `NO`     | **Primary Key** (e.g. `FAIL-20261005-001`). Unique failure audit record.                                                  |
| `idempotency_key`   | `VARCHAR(128)`  | `NO`     | Client `X-Idempotency-Key` (e.g. `IDEMP-7701`).                                                                           |
| `source_account_id` | `VARCHAR(32)`   | `NO`     | Debtor account.                                                                                                           |
| `target_account_id` | `VARCHAR(32)`   | `NO`     | Creditor account.                                                                                                         |
| `attempted_amount`  | `DECIMAL(18,2)` | `NO`     | Amount attempted.                                                                                                         |
| `failure_stage`     | `VARCHAR(64)`   | `NO`     | Failure point: `NETWORK_TIMEOUT`, `CBS_REJECTED`, `LOCK_ACQUISITION_TIMEOUT`, `CIRCUIT_BREAKER_TRIPPED`.                  |
| `error_code`        | `VARCHAR(32)`   | `NO`     | Error classification: `HTTP_504`, `OFS_TIMEOUT`, `MAX_RETRIES_EXCEEDED`.                                                  |
| `error_message`     | `VARCHAR(500)`  | `NO`     | Diagnostic technical details.                                                                                             |
| `retry_count`       | `INT`           | `NO`     | Number of retry attempts made (1, 2, 3).                                                                                  |
| `resolution_status` | `VARCHAR(32)`   | `NO`     | Final state: `RECOVERED_ON_RETRY`, `RECOVERED_ALREADY_SETTLED`, **`FAILED_EXHAUSTED`**, `MANUAL_RECONCILIATION_REQUIRED`. |
| `dlq_topic`         | `VARCHAR(128)`  | `YES`    | Dead Letter Queue Kafka topic name if dispatched (`banking.transfers.dlq`).                                               |
| `created_at`        | `DATETIME2`     | `NO`     | First failure timestamp.                                                                                                  |
| `last_attempt_at`   | `DATETIME2`     | `NO`     | Final retry attempt timestamp.                                                                                            |

---

## 3. Feature 1: Transaction Reversal

### 3.1 Overview

- **Initiation:** Triggered by teller operational error or customer dispute with mandatory `reversal_reason` and unique `reversal_id`.
- **CBS Validation:** T24 CBS queries Azure SQL to ensure original transaction was `SETTLED` and recipient has sufficient available balance.
- **Audit Persistence:** T24 inserts a record into `transaction_reversal_history`. If clawback succeeds, status is explicitly set to **`REVERSED`**. If recipient funds are insufficient, status is set to **`FAILED`** with `failure_reason = INSUFFICIENT_FUNDS_FOR_CLAWBACK`.

### 3.2 Sequence Diagram: Transaction Reversal

```mermaid
sequenceDiagram
    autonumber
    actor Ops as Teller / Ops / Dispute Officer
    participant GW as API Gateway :8080
    participant Redis as Token Cache :6379
    participant Orch as Transfer Orchestrator :8082
    participant T24 as T24 Mock CBS :8085
    participant SQL as Azure SQL DB (Master Ledgers)
    participant Kafka as Event Stream :9092
    participant Vault as Audit Vault :5432
    participant Notif as Notification Service :8083

    Ops->>+GW: POST /api/v1/transfers/TXN_ID/reverse (JWT, ReversalRequest JSON with reversal_reason)
    GW->>Redis: Check session and role permissions
    Redis-->>GW: Token Valid (ROLE_TELLER / ROLE_SUPERVISOR)
    GW->>+Orch: Forward Reversal Request

    Orch->>Orch: Policy Check (Window within 24h, Segregation of Duties)
    Orch->>Orch: Generate Reversal ID (reversal_id = REV-99101)
    Orch->>Orch: Translate to OFS: FUNDS.TRANSFER,REVERSE/R/PROCESS/0/1...

    Orch->>+T24: POST /api/v1/t24/ofs (text/plain: OFS Reversal Wire Payload)

    T24->>+SQL: Query original transaction by TXN_ID
    SQL-->>-T24: Original Transaction Found (Amount: PHP 10000.00, Status: SETTLED)

    T24->>+SQL: Read recipient account balance with UPDLOCK
    SQL-->>-T24: Recipient Available Balance = PHP 14500.00

    alt Recipient Has Sufficient Funds (Clawback Approved)
        T24->>SQL: Debit recipient 10000 and Credit sender 10000
        T24->>SQL: Update transaction_master status = REVERSED
        T24->>SQL: Insert transaction_reversal_history (reversal_id = REV-99101, status = REVERSED, reason = OPERATIONAL_ERROR)
        SQL-->>T24: Reversal Committed to Master Ledgers

        T24-)Kafka: Publish TransactionReversedEvent (topic: banking.transfers.events)
        T24-->>Orch: 200 OK (OFS: TXN_ID//1/REVERSED,REVERSAL_ID=REV-99101)

        Kafka-)Vault: Project Reversal Audit to PostgreSQL
        Orch->>Notif: POST /api/v1/notifications/alert (Reversal Receipt Alert)
        Notif-->>Ops: Email and SMS Receipts Dispatched
        Orch-->>GW: 200 OK (ReversalResponseDTO: reversal_id = REV-99101, status = REVERSED)
        GW-->>Ops: 200 OK (Reversal Confirmed)

    else Recipient Has Insufficient Funds (Clawback Failed)
        T24->>SQL: Insert transaction_reversal_history (reversal_id = REV-99101, status = FAILED, failure_reason = INSUFFICIENT_FUNDS)
        T24->>SQL: Rollback Balance Mutation
        T24-->>Orch: 422 Unprocessable (OFS: TXN_ID//-1/NO,ERROR=INSUFFICIENT_FUNDS)
        Orch-->>GW: 422 Unprocessable Entity (Reversal Failed)
        GW-->>Ops: 422 Error: Reversal status FAILED. Recipient balance insufficient for clawback.
    end
```

---

## 4. Feature 2: Amount Holds / Reservations (`AC.LOCKED.EVENTS`)

### 4.1 Overview

- **Initiation:** Triggered for high-value Maker-Checker transactions (> PHP 50,000.00) or pre-authorizations.
- **Balance Formula:**
  $$\text{available\_balance} = \text{available\_balance} - \text{hold\_amount}$$
  $$\text{locked\_amount} = \text{locked\_amount} + \text{hold\_amount}$$
- **Audit Persistence:** All status changes (`ACTIVE`, `CAPTURED`, `RELEASED`) are tracked in **`amount_hold_history`**.

### 4.2 Sequence Diagram: Amount Hold Lifecycle

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Customer / Channel
    actor Checker as Teller / Supervisor
    participant GW as API Gateway :8080
    participant Redis as Token Cache :6379
    participant Orch as Transfer Orchestrator :8082
    participant T24 as T24 Mock CBS :8085
    participant SQL as Azure SQL DB (Master CBS Ledgers)
    participant Kafka as Event Stream :9092
    participant Vault as Audit Vault :5432

    Customer->>+GW: POST /api/v1/accounts/ACC_ID/holds (Bearer JWT, HoldRequest JSON: PHP 60000.00)
    GW->>Redis: Check session and token blacklist
    Redis-->>GW: Token Valid (ROLE_CUSTOMER / ROLE_TELLER)
    GW->>+Orch: Forward Hold Placement Request

    Orch->>Orch: Generate Hold Reference (hold_id = HLD-99102)
    Orch->>Orch: Map to AC.LOCKED.EVENTS syntax: ACLK...
    Orch->>+T24: POST /api/v1/t24/ofs (text/plain: OFS Payload)

    T24->>+SQL: Query available_balance and locked_amount with UPDLOCK
    SQL-->>-T24: Available = PHP 100000.00, Locked = PHP 0.00

    T24->>T24: Validate available_balance is at least PHP 60000.00
    T24->>SQL: Update locked_amount + 60000 and available_balance - 60000
    T24->>SQL: Insert amount_hold_history (hold_id = HLD-99102, status = ACTIVE, amount = 60000.00)
    SQL-->>T24: Hold Record Created in amount_hold_history and Balances Mutated

    T24-)Kafka: Publish AmountHoldPlacedEvent (topic: banking.holds.events)
    T24-->>Orch: 201 Created (OFS: ACLK26095A//1/SUCCESS,HOLD.ID=HLD-99102)

    Kafka-)Vault: Project Hold Creation into PostgreSQL Audit Vault
    Orch-->>GW: 201 Created (HoldResponseDTO: hold_id = HLD-99102, status = ACTIVE)
    GW-->>Customer: 201 Created (Reservation Confirmed)

    Checker->>+GW: POST /api/v1/accounts/ACC_ID/holds/HLD-99102/capture (Bearer JWT)
    GW->>Redis: Verify supervisor role and token validity
    Redis-->>GW: Token Valid (ROLE_SUPERVISOR / ROLE_CHECKER)
    GW->>+Orch: Forward Capture Request

    Orch->>Orch: Verify Maker not equal to Checker segregation
    Orch->>Orch: Map to OFS: FUNDS.TRANSFER with HOLD.REF=HLD-99102
    Orch->>+T24: POST /api/v1/t24/ofs (FUNDS.TRANSFER Execution)

    T24->>SQL: Debit balance 60000 and locked_amount 60000 on source
    T24->>SQL: Credit balance 60000 on target
    T24->>SQL: Update amount_hold_history SET status = CAPTURED, reference_txn_id = FT26095C
    SQL-->>T24: ACID Transfer Committed

    T24-)Kafka: Publish TransferSettledEvent (Hold Released and Settled)
    T24-->>Orch: 200 OK (OFS: FT26095C//1/SUCCESS)

    Orch-->>GW: 200 OK (TransferReceiptDTO: Status: SETTLED)
    GW-->>Checker: 200 OK (Transfer Approved and Settled)

    Checker->>+GW: DELETE /api/v1/accounts/ACC_ID/holds/HLD-99102 (Bearer JWT)
    GW->>Redis: Verify supervisor role and token validity
    Redis-->>GW: Token Valid (ROLE_SUPERVISOR / ROLE_CHECKER)
    GW->>+Orch: Forward Release Request

    Orch->>Orch: Map to OFS: AC.LOCKED.EVENTS,REVERSE/R/PROCESS/0/1...
    Orch->>+T24: POST /api/v1/t24/ofs (Release Payload)

    T24->>SQL: Restore available_balance + 60000 and locked_amount - 60000
    T24->>SQL: Update amount_hold_history SET status = RELEASED, released_at = NOW
    SQL-->>T24: Funds Unlocked in Azure SQL

    T24-)Kafka: Publish AmountHoldReleasedEvent
    T24-->>Orch: 200 OK (OFS: HLD-99102//1/RELEASED)

    Orch-->>GW: 200 OK (HoldResponseDTO: status: RELEASED)
    GW-->>Checker: 200 OK (Hold Cancelled and Funds Restored)
```

---

## 5. Feature 3: Failed Transaction & Retry Management

### 5.1 Overview

- **Status Interrogation:** Prior to retrying after a network drop, Orchestrator interogates CBS using `FUNDS.TRANSFER,STATUS` with the `X-Idempotency-Key`.
- **Zero Double-Debit Guarantee:** If CBS already settled the transaction, Orchestrator skips re-execution and returns the existing receipt.
- **Audit Persistence:** Every failure event, retry outcome, and Dead Letter Queue dispatch is recorded into **`failed_transaction_history`** with `failure_id`, `failure_stage`, `error_code`, `retry_count`, and `resolution_status`.

### 5.2 Sequence Diagram: Retry Interrogation & Circuit Breaker / DLQ

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client Channels
    participant GW as API Gateway :8080
    participant Redis as Token and Idempotency Cache :6379
    participant Orch as Transfer Orchestrator :8082
    participant T24 as T24 Mock CBS :8085
    participant SQL as Azure SQL DB (Master CBS Ledgers)
    participant Kafka as Event Stream :9092
    participant Notif as Notification Service :8083

    Client->>+GW: POST /api/v1/transfers (Bearer JWT, Header: X-Idempotency-Key IDEMP-7701)
    GW->>Redis: Check session validity and token blacklist
    Redis-->>GW: Token Valid (ROLE_CUSTOMER)
    GW->>+Orch: Forward Transfer Request

    Orch->>Redis: SETNX idemp:IDEMP-7701 IN_FLIGHT (TTL 300s)
    Redis-->>Orch: OK (Lock Acquired)

    Orch->>Orch: Map to OFS: FUNDS.TRANSFER...
    Orch->>+T24: POST /api/v1/t24/ofs (FUNDS.TRANSFER Wire)

    T24--xOrch: Network Interruption / Socket Timeout / HTTP 504 Drop

    Orch->>Orch: Exponential Backoff (Attempt 1 of 3: Wait 500ms)

    Orch->>+T24: POST /api/v1/t24/ofs (Inquiry: FUNDS.TRANSFER,STATUS... IDEMP-7701)

    T24->>+SQL: Query transaction_master by idempotency_key IDEMP-7701
    SQL-->>-T24: Check Result

    alt Scenario A: Transaction was already committed on CBS
        T24-->>Orch: 200 OK (OFS: IDEMP-7701//1/COMMITTED,TXN.ID=FT26095A)
        Orch->>Redis: SET idemp:IDEMP-7701 SETTLED
        Orch-->>GW: 200 OK (TransferReceiptDTO: Already Settled)
        GW-->>Client: 200 OK (Transaction Receipt)

    else Scenario B: Transaction never reached CBS (Record Absent)
        T24-->>Orch: 404 Not Found (OFS: IDEMP-7701//-1/NO,ERROR=TXN_NOT_FOUND)

        Orch->>+T24: POST /api/v1/t24/ofs (Re-transmit OFS FUNDS.TRANSFER)

        alt Replay Succeeds
            T24->>SQL: Mutate Balances and Insert Record
            SQL-->>T24: Committed Successfully
            T24-)Kafka: Publish TransferSettledEvent
            T24-->>Orch: 200 OK (OFS: FT26095B//1/SUCCESS)
            Orch->>Redis: SET idemp:IDEMP-7701 SETTLED
            Orch-->>GW: 200 OK (TransferReceiptDTO)
            GW-->>Client: 200 OK (Transaction Success)

        else Replay Fails Repeatedly (Exhausted Retries)
            T24--xOrch: Timeout / Downstream Database Down
            Orch->>Orch: Trip Circuit Breaker after 3 Attempts
            Orch->>Redis: SET idemp:IDEMP-7701 FAILED_EXHAUSTED
            Orch->>SQL: Insert failed_transaction_history (failure_id = FAIL-8801, key = IDEMP-7701, stage = CIRCUIT_BREAKER_TRIPPED, status = FAILED_EXHAUSTED)
            Orch-)Kafka: Publish to Dead Letter Queue (topic: banking.dlq.transfers)
            Orch->>Notif: POST /api/v1/notifications/alert (Transfer Failed Alert)
            Notif-->>Client: Dispatch SMS and Email Alert
            Orch-->>GW: 504 Gateway Timeout (Transfer Exhausted to DLQ)
            GW-->>Client: 504 Error: Transfer status FAILED_EXHAUSTED. Logged in failed_transaction_history.
        end
    end
```

---

## 6. Temenos OFS Wire Syntax Mapping for the 3 Features

| Feature                  | Operation          | Temenos Application & Version            | Wire OFS Syntax Example                                                                                       |
| :----------------------- | :----------------- | :--------------------------------------- | :------------------------------------------------------------------------------------------------------------ |
| **Transaction Reversal** | Reversal Request   | `FUNDS.TRANSFER,REVERSE/R/PROCESS/0/1`   | `FUNDS.TRANSFER,REVERSE/R/PROCESS/0/1,U1001/PH100223/1,TX-01,REVERSAL.REASON=DISPUTE`                         |
| **Transaction Reversal** | Reversal Response  | CBS ACK                                  | `TX-01//1/REVERSED,ORIG.TXN.ID:1:1=TX-01,REV.REF:1:1=REV-88901`                                               |
| **Amount Holds**         | Create Hold        | `AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1`   | `AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1,U1001/PH100223/1,,ACCOUNT.NUMBER=1000-2000-3001,LOCKED.AMOUNT=60000.00` |
| **Amount Holds**         | Capture Hold       | `FUNDS.TRANSFER,AUTH/I/PROCESS/0/1`      | `FUNDS.TRANSFER,AUTH/I/PROCESS/0/1,U1001/PH100223/1,TX-02,HOLD.REF=HLD-99102,AMOUNT=60000.00`                 |
| **Amount Holds**         | Release Hold       | `AC.LOCKED.EVENTS,REVERSE/R/PROCESS/0/1` | `AC.LOCKED.EVENTS,REVERSE/R/PROCESS/0/1,U1001/PH100223/1,HLD-99102,REVERSAL.REASON=CANCELLED`                 |
| **Retry & Failure**      | Status Interrogate | `FUNDS.TRANSFER,STATUS/S/PROCESS/0/1`    | `FUNDS.TRANSFER,STATUS/S/PROCESS/0/1,U1001/PH100223/1,,IDEMP-7701`                                            |
| **Retry & Failure**      | Not Found (Safe)   | CBS Query Response                       | `IDEMP-7701//-1/NOT_FOUND`                                                                                    |
| **Retry & Failure**      | Already Committed  | CBS Query Response                       | `IDEMP-7701//1/COMMITTED,TXN.ID:1:1=FT26095A`                                                                 |
