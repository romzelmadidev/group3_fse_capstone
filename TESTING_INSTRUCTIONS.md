# Complete Banking Logic & T24 Core Testing Instructions Runbook

This testing runbook provides rigorous, step-by-step instructions to test, validate, and verify all banking logic implemented across Aura Bank's microservices architecture. It covers both the features testable via the **T24 Test Lab Front End** (`/t24-test`) and the advanced backend banking logic that must be tested via API requests, auxiliary portals, or background inspection tools.

---

## 1. System Architecture & Testing Environment Reference

### A. Microservices & Port Map

| Component / Service | Technology | Port | Primary Responsibility & Endpoints |
| :--- | :--- | :--- | :--- |
| **API Gateway** | Spring Cloud Gateway | `8080` | Perimeter routing, JWT auth, rate limiting (`/api/v1/**`) |
| **Account Service** | Spring Boot | `8081` | Authentication, KYC, account provisioning (`/api/v1/auth`, `/api/v1/kyc`, `/api/v1/accounts`) |
| **Transfer Orchestrator** | Spring Boot | `8082` | Transfer saga, idempotency, cooling-off, biometric challenges (`/api/v1/transfers`, `/api/v1/reversals`) |
| **Notification Service** | Spring Boot | `8083` | Email dispatch, SSE alert stream, OTPs (`/api/v1/notifications`, `/ws`) |
| **Risk Service (NanoJev)** | FastAPI (Python) | `8084` | Two-stage fraud scoring, geo-velocity, NLP memo checks (`/risk/decision`, `/risk/memo-check`) |
| **T24 Mock CBS** | Spring Boot | `8085` | Custodian of Master DB, OFS posting, COB/EOD batch, GL entries (`/api/v1/cbs/**`) |
| **Compliance Service** | Spring Boot | `8086` | PDF statements, Azurite blob integration, DLQ management (`/api/v1/compliance/**`) |
| **Frontend Web App** | React / Vite | `3000` | Customer Portal (`/customer`), Admin (`/admin`), Executive (`/admin-executive`), T24 Lab (`/t24-test`) |
| **MailHog SMTP Web UI** | Go Mock SMTP | `8025` | Live web inbox for transaction receipts and 2FA OTP emails (`http://localhost:8025`) |
| **Kafka Dashboard** | Kafka UI | `8089` | Real-time topic and message stream inspection (`http://localhost:8089`) |
| **Redis Insight** | Redis GUI | `5540` | Visual key and TTL browser (`http://localhost:5540`) |
| **Azurite Storage Web UI** | Azurite Drive | `10005` | Blob storage browser for compliance reports and statements (`http://localhost:10005`) |

### B. Pre-Seeded Test Accounts & Personas

* **Test Deposit Accounts**:
  * `1000-2000-3001` (Savings, initial balance: ~₱25,000,000.00)
  * `1000-2000-3002` (Savings, initial balance: ~₱5,000,000.00)
  * `1000-2000-3004` (Savings, initial balance: ~₱5,200,000.00)
  * `1000-2000-3005` (Savings, initial balance: ~₱3,750,000.00)
* **Pre-Configured User Personas**:
  * **Retail Customer**: Juan Dela Cruz (`userId`: `U1001`, `role`: `ROLE_CUSTOMER`)
  * **Administrator / Compliance Officer**: Diana Vance (`userId`: `U0001`, `role`: `ROLE_ADMIN`)

---

## 2. PART 1: Testing via the T24 Test Lab Front End (`/t24-test`)

Navigate in your web browser to **`http://localhost:3000/t24-test`** (or click the **"T24 Test Lab"** badge in the top navigation bar).

### How to Access and Query the Databases

You can verify the database state using either the **Command Line (Docker CLI)** or the **Adminer Web GUI**:

#### Method A: Command Line via Docker CLI (PowerShell / Bash)
* **Oracle XE Master Database** (Houses operational ledger, accounts, balances, transactions, and GL):
  ```powershell
  docker exec -i oracle-xe-master sqlplus -s fse_user/fse_password@//localhost:1521/XEPDB1
  ```
  *(Enter SQL queries followed by a semicolon `;` and type `exit;` when finished).*

* **PostgreSQL Dedicated Audit Vault** (Houses append-only immutable audit trail and DLQ incidents):
  ```powershell
  docker exec -i postgres-audit-vault psql -U audit_user -d banking_audit
  ```
  *(Enter SQL queries followed by a semicolon `;` and type `\q` when finished).*

#### Method B: Adminer Web Management GUI
* Open your browser at **`http://localhost:8088`**.
  * **To query Oracle XE Master**: System: `Oracle`, Server: `oracle-xe-master:1521/XEPDB1`, Username: `fse_user`, Password: `fse_password`, Database: `XEPDB1`.
  * **To query PostgreSQL Audit Vault**: System: `PostgreSQL`, Server: `postgres-audit-vault`, Username: `audit_user`, Password: `audit_password`, Database: `banking_audit`.

---

### Test 1.1: Intra-Bank Funds Transfer (`FUNDS.TRANSFER`)
* **Objective**: Verify standard funds transfer execution from Orchestrator JSON to Temenos OFS, atomic balance mutation, and double-entry General Ledger posting.
* **Steps**:
  1. In the **Select Test Account** card, choose `1000-2000-3001`.
  2. Note the initial **Working Balance** and **Available to Spend**.
  3. Click the **"1. Funds Transfer"** tab.
  4. Fill in the transfer details:
     * **Source Account**: `1000-2000-3001`
     * **Destination Account**: `1000-2000-3002`
     * **Amount**: `5000.00`
     * **Currency**: `PHP`
     * **Description**: `Test Transfer via CBS Console`
  5. Click **"Execute Funds Transfer (FUNDS.TRANSFER)"**.
* **Frontend Verification Checkpoints**:
  * A green toast notification appears: *"Funds Transfer executed and posted via Gateway ➔ Orchestrator ➔ T24 CBS!"*.
  * The **Working Balance** of `1000-2000-3001` decreases by exactly ₱5,000.00.
  * In the **Execution Evidence & OFS Raw Logs** card, confirm:
    * Outbound payload contains generated `TXN.ID` (e.g. `TXN-582910`) and `DEBIT.ACCT.NO=1000-2000-3001`.
    * Inbound CBS response indicates `STATUS=SUCCESS` and status code `1`.
  * The generated Transaction ID (e.g., `TXN-582910`) is automatically populated into the Reversal form.
  * Under the Execution Evidence card, click the **"Inspect Status Lifecycle History"** button. The modal opens and renders all 4 lifecycle transitions from Core Banking via Orchestrator (`Initiated` ➔ `Authorized` ➔ `Reserved` ➔ `Processing` ➔ `Posted`).

* **Database Table Verification Steps**:
  1. **Check `BALANCE_MASTER` Table (Oracle XE)**:
     ```sql
     SELECT account_id, balance_amount, hold_amount, available_balance, updated_at 
     FROM balance_master 
     WHERE account_id IN ('1000-2000-3001', '1000-2000-3002');
     ```
     * **Expected Invariant**:
       * `1000-2000-3001` (Sender): `balance_amount` decreased by exactly `5000.0000`.
       * `1000-2000-3002` (Receiver): `balance_amount` increased by exactly `5000.0000`.
       * Virtual column `available_balance` equals `balance_amount - hold_amount`.

  2. **Check `TRANSACTIONS` Table (Oracle XE)**:
     ```sql
     SELECT transaction_id, from_account_id, to_account_id, amount, currency, transaction_type, status, memo, created_at 
     FROM transactions 
     WHERE transaction_id = '<TXN_ID>';
     ```
     * **Expected Invariant**:
       * Record exists with `status = 'Posted'`.
       * `transaction_type = 'INTRA_BANK'`, `amount = 5000.0000`, `currency = 'PHP'`.
       * `from_account_id = '1000-2000-3001'`, `to_account_id = '1000-2000-3002'`.

  3. **Check `GL_LEDGER` Table (Oracle XE - Double-Entry Accounting)**:
     ```sql
     SELECT journal_id, transaction_id, gl_code, debit_amount, credit_amount, posting_date 
     FROM gl_ledger 
     WHERE transaction_id = '<TXN_ID>';
     ```
     * **Expected Invariant**:
       * Exactly **two balanced journal rows** for Customer Demand Deposits (`GL-20100`):
         * **Debit leg**: `gl_code = '20100'`, `debit_amount = 5000.0000`, `credit_amount = 0.0000` (sender deduction).
         * **Credit leg**: `gl_code = '20100'`, `debit_amount = 0.0000`, `credit_amount = 5000.0000` (beneficiary addition).
       * Total Debits equals Total Credits (`SUM(debit_amount) == SUM(credit_amount)`).

  4. **Check `TRANSACTION_STATUS_HISTORY` Table (Oracle XE - Complete Lifecycle Audit Trail)**:
     ```sql
     SELECT history_id, transaction_id, from_status, to_status, change_reason, reason_details, actor_id, actor_type, changed_at 
     FROM transaction_status_history 
     WHERE transaction_id = '<TXN_ID>' 
     ORDER BY changed_at ASC;
     ```
     * **Expected Invariant**:
       * Exactly **four chronological lifecycle transition records** are saved for the funds transfer:
         1. `from_status = NULL` (or `Initiated`) ➔ `to_status = 'Authorized'`: `change_reason = 'BIOMETRIC_AUTH_VERIFIED'`, `actor_id = '1000-2000-3001'`, `actor_type = 'CUSTOMER'`.
         2. `from_status = 'Authorized'` ➔ `to_status = 'Reserved'`: `change_reason = 'FUNDS_RESERVATION_EARMARKED'`, `actor_id = 'SYSTEM_CBS'`, `actor_type = 'SYSTEM_CBS'`.
         3. `from_status = 'Reserved'` ➔ `to_status = 'Processing'`: `change_reason = 'CBS_OFS_PROCESSING'`, `actor_id = 'SYSTEM_CBS'`, `actor_type = 'SYSTEM_CBS'`.
         4. `from_status = 'Processing'` ➔ `to_status = 'Posted'`: `change_reason = 'ACID_LEDGER_COMMITTED'`, `actor_id = 'SYSTEM_CBS'`, `actor_type = 'SYSTEM_CBS'`.

  5. **Check `OUTBOX_EVENTS` Table (Oracle XE - Transactional Outbox)**:
     ```sql
     SELECT event_id, aggregate_type, aggregate_id, event_type, kafka_topic, status 
     FROM outbox_events 
     WHERE aggregate_id = '<TXN_ID>';
     ```
     * **Expected Invariant**:
       * Record exists with `event_type = 'TransferExecutedEvent'`, `kafka_topic = 'banking.transfers.events'`, and `status = 'PUBLISHED'` (or `'PENDING'`).

  6. **Check `ledger_mutation_audit` Table (PostgreSQL Audit Vault)**:
     ```sql
     SELECT audit_id, transaction_id, account_id, mutation_type, mutation_amount, before_balance, after_balance, status, sha256_hash 
     FROM ledger_mutation_audit 
     WHERE transaction_id = '<TXN_ID>';
     ```
     * **Expected Invariant**:
       * Two append-only records logged (`DEBIT` on source, `CREDIT` on destination).
       * `sha256_hash` is populated with a 64-character cryptographic hash anchoring the mutation.

---

### Test 1.2: Transaction & Audit History Enquiry (`ENQUIRY.SELECT`)
* **Objective**: Verify account ledger history enquiry and state transition audit logs.
* **Steps**:
  1. Click the **"2. Transaction Enquiry (ENQUIRY.SELECT)"** tab.
  2. Ensure the enquiry account is set to `1000-2000-3001`.
  3. Click **"Execute Query"**.
* **Frontend Verification Checkpoints**:
  * The ledger table renders the transaction list matching the Temenos `ENQUIRY.SELECT` dataset.
  * The transfer from **Test 1.1** appears with `Amount: ₱5,000.00`, `Type: INTRA_BANK`, and `Status: POSTED`.
  * Click the **"History"** / **"Audit Status Timeline"** button on the transaction row.
  * A modal opens showing the complete audit progression from `TransactionStatusHistoryMaster`:
    * Step 1: `INITIATED` ➔ `Authorized` (`BIOMETRIC_AUTH_VERIFIED` — Customer authorization and risk validation passed)
    * Step 2: `Authorized` ➔ `Reserved` (`FUNDS_RESERVATION_EARMARKED` — Funds reservation earmarked in core balance)
    * Step 3: `Reserved` ➔ `Processing` (`CBS_OFS_PROCESSING` — Core OFS transaction processing initiated)
    * Step 4: `Processing` ➔ `Posted` (`ACID_LEDGER_COMMITTED` — ACID double-entry ledger posting committed)

* **Database & Direct Endpoint Verification Steps**:
  1. **Verify On-Screen Records Match `TRANSACTIONS` (Oracle XE)**:
     ```sql
     SELECT transaction_id, from_account_id, to_account_id, amount, currency, status, created_at 
     FROM transactions 
     WHERE from_account_id = '1000-2000-3001' OR to_account_id = '1000-2000-3001' 
     ORDER BY created_at DESC FETCH FIRST 10 ROWS ONLY;
     ```
     * **Expected Invariant**: The record count, transaction IDs, and amounts in the database match the UI list.

  2. **Verify Modal Lifecycle Matches `TRANSACTION_STATUS_HISTORY` (Oracle XE)**:
     ```sql
     SELECT history_id, from_status, to_status, change_reason, reason_details, actor_id, actor_type, changed_at 
     FROM transaction_status_history 
     WHERE transaction_id = '<TXN_ID>' 
     ORDER BY changed_at ASC;
     ```
     * **Expected Invariant**: Displays the sequential audit states for the transaction matching the modal inspector.

  3. **Verify Direct Status History Endpoints (OFS & REST)**:
     * **Via Transfer Orchestrator (JSON)**:
       ```bash
       curl -s "http://localhost:8080/api/v1/transfers/transactions/<TXN_ID>/status-history?page=0&size=20"
       ```
       *Returns JSON array of status transition objects with history IDs, statuses, and reasons.*
     * **Via T24 CBS Core (Temenos OFS Wire Syntax)**:
       ```bash
       curl -s "http://localhost:8085/api/v1/cbs/transactions/<TXN_ID>/status-history?page=0&size=20"
       ```
       *Returns raw Temenos OFS syntax:*
       `TRANSACTION.STATUS.HISTORY,ENQUIRY/I/PROCESS//<TXN_ID>,TOTAL.RECORDS:4,PAGE:0,SIZE:20,RECORD.1:...,RECORD.2:...`

---

### Test 1.3: Four-Eyes Maker-Checker Reversals (BSP Circular 982)
* **Objective**: Verify the dual-control reversal lifecycle where a Maker requests a reversal and an independent Checker approves it.
* **Steps**:
  1. Click the **"3. Reversal (Dual Control)"** tab.
  2. Under **Step 1: Maker Reversal Request**:
     * **Original Transaction ID**: Enter the Transaction ID from Test 1.1.
     * **Maker ID**: `usr-1003-tel-001` (Crisostomo Ibarra - Teller)
     * **Dispute Reason**: Select `CUSTOMER_DISPUTE`.
     * **Notes**: `Customer disputed charge`.
     * Click **"Submit Reversal Request"**.
  3. **Verification**: Toast confirms *"Reversal ticket created via Orchestrator! Waiting for Checker approval."* The transaction status becomes `PendingReversal`, and the generated `Ticket ID` is auto-filled into the Checker form.
  4. Under **Step 2: Checker Authorization (Four-Eyes Principle)**:
     * Verify **Ticket ID** matches the generated ticket.
     * **Checker ID**: Ensure it is set to an independent user (`usr-1004-adm-001` — Diana Administrator, Admin).
     * Click **"Authorize Reversal (Approve)"**.
* **Frontend Verification Checkpoints**:
  * Toast confirms *"Reversal APPROVED via Orchestrator! Compensating GL entries posted and balances reversed."*.
  * The **Working Balance** of `1000-2000-3001` increases by ₱5,000.00 (refunded).
  * In the **Live Reversal Requests Backlog** table below, the ticket status changes to `APPROVED`.
  * Click the **"History"** button on the approved reversal row to view the full audit progression modal from `TransactionStatusHistoryMaster`.
  * Selecting `1000-2000-3002` confirms its balance was debited back by ₱5,000.00.

* **Database Table Verification Steps**:

  #### Phase A: Immediately After Maker Submits Dispute Request
  1. **Check `REVERSAL_REQUESTS` Table (Oracle XE)**:
     ```sql
     SELECT ticket_id, original_tx_id, maker_id, checker_id, dispute_reason, maker_notes, status, created_at 
     FROM reversal_requests 
     WHERE original_tx_id = '<ORIGINAL_TX_ID>';
     ```
     * **Expected Invariant**:
       * Record exists with `status = 'PENDING'`.
       * `maker_id = 'usr-1003-tel-001'`, `checker_id IS NULL`.
       * `dispute_reason = 'CUSTOMER_DISPUTE'`.

  2. **Check `TRANSACTIONS` Table (Oracle XE)**:
     ```sql
     SELECT transaction_id, status, updated_at 
     FROM transactions 
     WHERE transaction_id = '<ORIGINAL_TX_ID>';
     ```
     * **Expected Invariant**:
       * `status` transitioned from `Posted` to `PendingReversal`.

  3. **Check `TRANSACTION_STATUS_HISTORY` Table (Oracle XE)**:
     ```sql
     SELECT from_status, to_status, change_reason, reason_details, actor_id, actor_type 
     FROM transaction_status_history 
     WHERE transaction_id = '<ORIGINAL_TX_ID>' 
     ORDER BY changed_at DESC FETCH FIRST 1 ROWS ONLY;
     ```
     * **Expected Invariant**:
       * `from_status = 'Posted'`, `to_status = 'PendingReversal'`.
       * `change_reason = 'MAKER_DISPUTE_FILED'`, `actor_id = 'usr-1003-tel-001'`, `actor_type = 'TELLER_MAKER'`.

  #### Phase B: Immediately After Checker Authorizes Reversal
  1. **Check `REVERSAL_REQUESTS` Table (Oracle XE)**:
     ```sql
     SELECT ticket_id, original_tx_id, maker_id, checker_id, status, reversal_tx_id, resolved_at 
     FROM reversal_requests 
     WHERE original_tx_id = '<ORIGINAL_TX_ID>';
     ```
     * **Expected Invariant**:
       * `status` updated to `APPROVED`.
       * `checker_id = 'usr-1004-adm-001'`.
       * `reversal_tx_id` is populated with a generated UUID (e.g. `REV-TXN-...`).
       * `resolved_at` timestamp is populated.

  2. **Check `BALANCE_MASTER` Table (Oracle XE - Balance Reversion)**:
     ```sql
     SELECT account_id, balance_amount, available_balance 
     FROM balance_master 
     WHERE account_id IN ('1000-2000-3001', '1000-2000-3002');
     ```
     * **Expected Invariant**:
       * `1000-2000-3001` (Original Sender): Refunded +`5000.0000` back to initial balance.
       * `1000-2000-3002` (Original Beneficiary): Debited -`5000.0000` back to initial balance.

  3. **Check `TRANSACTIONS` Table (Oracle XE - Reversal Records)**:
     ```sql
     -- Check original transaction:
     SELECT transaction_id, status FROM transactions WHERE transaction_id = '<ORIGINAL_TX_ID>';
     -- Check generated compensating reversal transaction:
     SELECT transaction_id, from_account_id, to_account_id, amount, transaction_type, status, approved_by 
     FROM transactions 
     WHERE transaction_id = '<REVERSAL_TX_ID>';
     ```
     * **Expected Invariant**:
       * Original transaction status updated to `Reversed`.
       * New compensating transaction created with:
         * `transaction_id = '<REVERSAL_TX_ID>'`.
         * `from_account_id = '1000-2000-3002'` (beneficiary), `to_account_id = '1000-2000-3001'` (sender).
         * `transaction_type = 'REVERSAL'`, `status = 'Reversed'`, `approved_by = 'usr-1004-adm-001'`.

  4. **Check `TRANSACTION_STATUS_HISTORY` Table (Oracle XE - Lifecycle Transitions)**:
     ```sql
     -- 1. Status history for original transaction:
     SELECT from_status, to_status, change_reason, reason_details, actor_id, actor_type 
     FROM transaction_status_history 
     WHERE transaction_id = '<ORIGINAL_TX_ID>' 
     ORDER BY changed_at DESC FETCH FIRST 1 ROWS ONLY;
     -- Expected: from_status = 'PendingReversal', to_status = 'Reversed', change_reason = 'CHECKER_REVERSAL_APPROVED_SETTLED', actor_id = 'usr-1004-adm-001', actor_type = 'MANAGER_CHECKER'

     -- 2. Status history for generated compensating transaction:
     SELECT from_status, to_status, change_reason, reason_details, actor_id, actor_type 
     FROM transaction_status_history 
     WHERE transaction_id = '<REVERSAL_TX_ID>' 
     ORDER BY changed_at ASC;
     -- Expected: 3 sequential rows:
     -- (1) NULL -> 'Initiated' (CHECKER_REVERSAL_APPROVED_SETTLED, actor: usr-1004-adm-001)
     -- (2) 'Initiated' -> 'Processing' (CBS_OFS_PROCESSING, actor: SYSTEM_CBS)
     -- (3) 'Processing' -> 'Reversed' (CHECKER_REVERSAL_APPROVED_SETTLED, actor: usr-1004-adm-001)
     ```

  5. **Check `GL_LEDGER` Table (Oracle XE - Compensating Journal Entries)**:
     ```sql
     SELECT journal_id, transaction_id, gl_code, debit_amount, credit_amount 
     FROM gl_ledger 
     WHERE transaction_id = '<REVERSAL_TX_ID>';
     ```
     * **Expected Invariant**:
       * Exactly two compensating GL entries reversing the original entries:
         * Debit entry: `gl_code = '20100'`, `debit_amount = 5000.0000`, `credit_amount = 0.0000`.
         * Credit entry: `gl_code = '20100'`, `debit_amount = 0.0000`, `credit_amount = 5000.0000`.

  6. **Check `ledger_mutation_audit` Table (PostgreSQL Audit Vault)**:
     ```sql
     SELECT audit_id, transaction_id, mutation_type, mutation_amount, status 
     FROM ledger_mutation_audit 
     WHERE transaction_id = '<REVERSAL_TX_ID>';
     ```
     * **Expected Invariant**:
       * Append-only record committed with `mutation_type = 'REVERSAL'` and `status = 'COMMITTED'`.

---

### Test 1.4: Direct Compensating Saga Reversals
* **Objective**: Verify automated, zero-human-intervention compensating saga rollbacks orchestrated by the microservice event saga.
* **Architectural Context**:
  * In distributed transactions and saga orchestrations, when downstream fulfillment fails or an upstream client issues an immediate rollback, the saga coordinator executes a direct compensating reversal (`POST /api/v1/reversals/direct`).
  * **Zero Defaults & Dual-Control Decoupling**:
    * Unlike manual customer dispute tickets (which strictly require human Maker and Checker authorization under BSP Circular 982), an automated SAGA compensating rollback is a **machine-to-machine fault tolerance mechanism**.
    * It does **not** require, expect, or default any human `makerId` or `checkerId`.
    * In the database, `requires_maker_checker = 0`, and `approved_by_user_id = NULL`. Because the foreign key column is nullable, Oracle constraints (`FK_TX_APPROVED_BY`) are preserved with zero violations.
    * In audit history (`transaction_status_history`), the action is recorded with `actor_id = 'SYSTEM_SAGA'` and `actor_type = 'SYSTEM_ORCH'`.
* **Steps**:
  1. Execute a new transfer of ₱2,500.00 in Tab 1 (e.g. from `1000-2000-3001` to `1000-2000-3002`).
  2. Copy the resulting Transaction ID, or note the auto-filled ID.
  3. Navigate to Tab 3 (**Reversal**).
  4. Under the **Direct Compensating Saga** card, verify the Transaction ID is present.
  5. Click **"Execute Direct Compensating Rollback"**.
* **Frontend Verification Checkpoints**:
  * Toast confirms *"Orchestrator compensating saga reversal executed via Gateway!"*.
  * The response payload displays `STATUS: SUCCESS`, `MESSAGE: REVERSAL_APPROVED_AND_SETTLED`, and the generated compensating transaction ID (e.g. `TXN-REV-XXXXXXXX`).
  * The working balances of both source and destination accounts are immediately restored without waiting for a manual Checker queue.

* **Database Table Verification Steps**:
  1. **Check Original and Compensating `TRANSACTIONS` Records (Oracle XE)**:
     ```sql
     -- Verify original transaction status transitioned to Reversed:
     SELECT transaction_id, from_account_id, to_account_id, amount, status 
     FROM transactions 
     WHERE transaction_id = '<ORIGINAL_TX_ID>';
     -- Expected: status = 'Reversed'

     -- Verify generated compensating transaction:
     SELECT transaction_id, from_account_id, to_account_id, amount, transaction_type, status, requires_maker_checker, approved_by, approved_by_user_id 
     FROM transactions 
     WHERE from_account_id = '1000-2000-3002' AND to_account_id = '1000-2000-3001' AND transaction_type = 'REVERSAL'
     ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY;
     -- Expected: status = 'Reversed', requires_maker_checker = 0, approved_by IS NULL, approved_by_user_id IS NULL
     ```

  2. **Check `BALANCE_MASTER` Table (Oracle XE)**:
     ```sql
     SELECT account_id, balance_amount, available_balance 
     FROM balance_master 
     WHERE account_id IN ('1000-2000-3001', '1000-2000-3002');
     ```
     * **Expected Invariant**: Both accounts restored to their pre-transfer balances (+₱2,500.00 to sender, -₱2,500.00 from beneficiary).

  3. **Check `GL_LEDGER` Table (Oracle XE)**:
     ```sql
     SELECT journal_id, gl_code, debit_amount, credit_amount 
     FROM gl_ledger 
     WHERE transaction_id = '<COMPENSATING_TXN_ID>';
     ```
     * **Expected Invariant**: Exactly two balanced entries on GL code `20100` (Debit: 2500.00, Credit: 2500.00).

  4. **Check `TRANSACTION_STATUS_HISTORY` Table (Oracle XE)**:
     ```sql
     SELECT history_id, transaction_id, from_status, to_status, change_reason, actor_id, actor_type 
     FROM transaction_status_history 
     WHERE transaction_id = '<ORIGINAL_TX_ID>' 
     ORDER BY changed_at DESC FETCH FIRST 1 ROWS ONLY;
     ```
     * **Expected Invariant**: `to_status = 'Reversed'`, `actor_id = 'SYSTEM_SAGA'`, `actor_type = 'SYSTEM_ORCH'`.

---

### Test 1.5: Dead Letter Queue (DLQ) Incident Browsing & Replay
* **Objective**: Verify failed transaction capture in PostgreSQL audit vault and recovery via DLQ replay during core banking outages.
* **Steps**:
  1. **Simulate a Genuine Core Outage**:
     In a terminal / PowerShell, temporarily pause the Core Banking container to simulate an upstream network timeout or core outage:
     ```powershell
     docker pause group3-t24-mock-cbs
     ```
  2. **Trigger a Funds Transfer During the Outage**:
     In the T24 Test Console (Tab 1) or via `curl`, dispatch a funds transfer:
     ```powershell
     curl.exe -s -X POST http://localhost:8080/api/v1/transfers `
       -H "Content-Type: application/json" `
       -d '{\"transactionId\":\"TXN-OUTAGE-001\",\"sourceAccountId\":\"1000-2000-3001\",\"destinationAccountId\":\"1000-2000-3002\",\"amount\":5000.00,\"currency\":\"PHP\",\"description\":\"Outage Resilience Test\",\"idempotencyKey\":\"OUTAGE-001\",\"scamAdvisoryAcknowledged\":true}'
     ```
     *Response*: Orchestrator attempts retries, trips Resilience4j circuit breaker fallback, routes `TransferFailedToDlqEvent` to Kafka topic `banking.transfers.dlq`, and returns fallback response:
     ```json
     {"transactionId":"TXN-OUTAGE-001","status":"Failed","message":"CBS temporarily unavailable. Transfer queued to DLQ for resolution."}
     ```
  3. **Restore Core Banking Service**:
     Unpause the CBS container:
     ```powershell
     docker unpause group3-t24-mock-cbs
     ```
     *(The CBS audit self-consumption worker consumes the event from `banking.transfers.dlq` and commits the incident into the PostgreSQL `failed_transaction_audit` table)*.
  4. **Browse and Replay the Dead-Lettered Incident in Tab 4**:
     * In the T24 Lab, navigate to the **"4. DLQ Incident Replays"** tab and click **"Refresh Incidents"**.
     * Locate `TXN-OUTAGE-001` in the DLQ Incidents backlog with `Circuit Breaker: OPEN` and `Status: PENDING_REPLAY`.
     * Click the **"Replay Transaction"** button (or execute `POST http://localhost:8080/api/v1/compliance/dlq/replay/TXN-OUTAGE-001`).
* **Frontend Verification Checkpoints**:
  * Green toast confirms *"DLQ transfer TXN-OUTAGE-001 replayed successfully!"*.
  * The incident status changes to resolved in the PostgreSQL audit vault and the transfer executes on the restored core.

* **Database Table Verification Steps**:
  1. **Check `failed_transaction_audit` Table Upon Failure Capture (PostgreSQL)**:
     ```sql
     SELECT incident_id, correlation_id, transaction_id, error_type, error_code, circuit_breaker_state, replay_status, failure_timestamp 
     FROM failed_transaction_audit 
     ORDER BY failure_timestamp DESC LIMIT 1;
     ```
     * **Expected Invariant**:
       * Row exists with `error_type = 'CBS_CIRCUIT_BREAKER_OR_TIMEOUT'`, `error_code = 'CBS_DOWN_DLQ_ROUTED'`.
       * `circuit_breaker_state = 'OPEN'`, `replay_status = 'PENDING_REPLAY'`.

  2. **Check `failed_transaction_audit` Table Upon Replay (PostgreSQL)**:
     ```sql
     SELECT incident_id, transaction_id, replay_status, resolved_at, resolved_by 
     FROM failed_transaction_audit 
     WHERE transaction_id = 'TXN-OUTAGE-001';
     ```
     * **Expected Invariant**:
       * `replay_status` updated to `'RESOLVED'`.
       * `resolved_by = 'SYSTEM'` (or compliance operator) and `resolved_at` is populated.

---

### Test 1.6: Banking Failure Simulator (Pre-Configured Invariants)
* **Objective**: Validate the core banking boundary rules and invariants.
* **Steps**:
  1. Click the **"5. Banking Failure Simulator"** tab.
  2. Run each test scenario by clicking **"Run Test"**:

     * **Scenario 1: Insufficient Funds (Solvency Test)**
       * *Payload*: ₱999,999,999.00.
       * *Pass Criteria*: HTTP 400 Bad Request with `Insufficient funds` message.
       * **Database Verification (Oracle XE)**:
         ```sql
         SELECT balance_amount, hold_amount, available_balance FROM balance_master WHERE account_id = '1000-2000-3001';
         SELECT COUNT(*) FROM transactions WHERE transaction_id LIKE 'SCEN-INS-%';
         ```
         *Expected Invariant*: Balance is 100% unchanged; count is `0`. Zero database mutations.

     * **Scenario 2: Circular Same-Account Transfer**
       * *Payload*: Source == Destination.
       * *Pass Criteria*: HTTP 400 rejection: `Source and destination accounts must be different.`
       * **Database Verification (Oracle XE)**:
         ```sql
         SELECT COUNT(*) FROM transactions WHERE transaction_id LIKE 'SCEN-CIRC-%';
         ```
         *Expected Invariant*: Count is `0`. Blocked at validation boundary before touching database.

     * **Scenario 3: Non-Existent Account (Routing Failure)**
       * *Payload*: Destination `ACC-INVALID-999-NOTFOUND`.
       * *Pass Criteria*: HTTP 400 rejection: `Account balance not found for ID: ACC-INVALID-999-NOTFOUND`.
       * **Database Verification (Oracle XE)**:
         ```sql
         SELECT COUNT(*) FROM transactions WHERE transaction_id LIKE 'SCEN-404-%';
         ```
         *Expected Invariant*: Count is `0`. No orphan transactions created.

     * **Scenario 4: Anti-Scam Cooling-Off Period (BSP Circular 1140)**
       * *Payload*: ₱300,000.00.
       * *Pass Criteria*: Status `Reserved`, `coolingOffRequired=true`, and 600-second lock (`coolingOffExpiresInSeconds=600`).
       * **Database & Cache Verification (Oracle XE & Redis)**:
         ```sql
         -- Verify balances in Oracle XE are untouched:
         SELECT balance_amount, hold_amount FROM balance_master WHERE account_id = '1000-2000-3001';
         -- Verify NO transaction or status history rows exist in CBS Master DB:
         SELECT COUNT(*) FROM transactions WHERE transaction_id LIKE 'SCEN-COOL-%';
         SELECT COUNT(*) FROM transaction_status_history WHERE transaction_id LIKE 'SCEN-COOL-%';
         ```
         *Expected Invariant*: Count is `0` for both tables. CBS enforces pure OFS wire protocol and does not receive premature hold records; the entire transfer request is decoupled and safely held in Redis under key `tx:cooloff:SCEN-COOL-...` (TTL 600s). Balances remain unmutated until settlement.

     * **Scenario 5: Strong Customer Authentication (SCA) Step-Up**
       * *Payload*: ₱75,000.00 without biometric signature.
       * *Pass Criteria*: Status `Authorized`, `biometricRequired=true`, and cryptographic challenge token issued.
       * **Database Verification (Oracle XE)**:
         *Expected Invariant*: No mutation in `balance_master` until biometric challenge token is verified.

     * **Scenario 6: Four-Eyes Dual-Control Violation (BSP Circular 982)**
       * *Payload*: Checker ID == Maker ID (`usr-1003-tel-001`).
       * *Pass Criteria*: HTTP 400 rejection: `Dual control violation: Checker ID cannot match Maker ID`.
       * **Database Verification (Oracle XE)**:
         ```sql
         SELECT ticket_id, status, checker_id FROM reversal_requests WHERE maker_id = 'usr-1003-tel-001' AND original_tx_id LIKE 'TXN-DISP-%';
         ```
         *Expected Invariant*: Ticket status remains `PENDING`; `checker_id` remains `NULL`. The self-approval was blocked by business logic before committing.

     * **Scenario 7: Idempotency Duplicate Replay**
       * *Payload*: Re-sending transfer with identical `idempotencyKey`.
       * *Pass Criteria*: Status `Posted` / `IDEMPOTENT_REPLAY` without double-debiting.
       * **Database Verification (Oracle XE)**:
         ```sql
         SELECT COUNT(*) FROM transactions WHERE idempotency_key LIKE 'IDEMP-TEST-%';
         ```
         *Expected Invariant*: Count is exactly `1`. Deduplication prevented duplicate row insertion and prevented double-debiting `balance_master`.

     * **Scenario 8: DLQ Incident Audit Vault & Replay Pipeline**
       * *Action*: Verifies live PostgreSQL DLQ Audit Vault accessibility and executes automatic replay recovery for pending dead-lettered incidents via the Compliance Service pipeline.
       * *Pass Criteria*: Audit Vault returns healthy status; if an unresolved incident is present, it is successfully replayed through the Transfer Orchestrator.
       * **Database Verification (PostgreSQL Audit Vault)**:
         `sql
         SELECT incident_id, transaction_id, error_type, circuit_breaker_state, replay_status, resolved_at 
         FROM failed_transaction_audit 
         ORDER BY failure_timestamp DESC LIMIT 1;
         `
         *Expected Invariant*: DLQ audit trail records outage failures (circuit_breaker_state = 'OPEN') and reflects 
eplay_status = 'RESOLVED' once replayed.

---

## 3. PART 2: Testing Bank Logic UNABLE to be Tested via the T24 Lab Front End

These core banking operations are implemented in the backend microservices but are not exposed or completed within the T24 Test Lab front end. Follow these concrete runbooks to execute and verify them.

---

### Test 2.1: Close of Business (COB) / EOD Batch Processing
Implemented in [`CbsCobBatchService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsCobBatchService.java) and [`CobController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CobController.java).

#### Step 1: Check System Business Date and Window Status
Run in terminal (PowerShell or Bash):
```bash
curl -s http://localhost:8085/api/v1/cbs/system-date
```
*Expected Output*:
```text
SYSTEM.DATE/I/PROCESS//SYS-DATE-1,BUSINESS.DATE:2026-10-09,STATUS:ONLINE,POSTING.WINDOW.OPEN:true
```

#### Step 2: Trigger Close of Business (COB) Batch Run
```bash
curl -X POST http://localhost:8085/api/v1/cbs/cob/run
```
*Expected Output*:
```text
COB.RUN/I/PROCESS//BATCH-...,ACCOUNTS.PROCESSED:4,FEES.COLLECTED:50.00,INTEREST.ACCRUED:...,STATUS:COMPLETED,MESSAGE:COB batch execution completed
```

#### Step 3: Verify the 5 Batch Execution Phases
1. **Phase 0 (Cutoff)**: The posting window transitions to `EOD_CUTOFF` and `POSTING.WINDOW.OPEN: false`.
2. **Phase 1 (ADB Fee Deduction)**: Accounts with Average Daily Balance (ADB) below ₱5,000.00 (`MIN_ADB_THRESHOLD`) are debited ₱50.00 monthly fee, credited to Fee Income `GL-4001`. *Note*: Standard test accounts with balances above ₱5,000.00 do not incur below-min fees by design. Insolvent accounts are logged into `UNCOLLECTED_FEE_MASTER`.
3. **Phase 2 (Daily Interest Accrual & BIR Withholding Tax)**: Computes 0.5% p.a. daily interest based on cleared balance, withholds 20% BIR tax, and records entries into the `INTEREST_ACCRUALS` table with `is_capitalized = 0`. *Note*: Interest accrues daily in the accrual ledger without mutating `balance_master` daily (capitalization occurs at cycle close).
4. **Phase 3 (GL Reconciliation Tripwire & Snapshot)**: Freezes immutable account balances into `EOD_BALANCE_SNAPSHOTS`. Emits `BalanceSnapshotFrozenEvent` to Kafka topic `banking.batch.events` which triggers compliance report generation in `compliance-service`.
5. **Phase 4 (Date Rollover)**: Business date advances to $T+1$ (`2026-10-10`), and the posting window re-opens (`ONLINE`). The single canonical record `SYS-DATE-1` in `SYSTEM_DATES` is updated in-place.

#### Step 4: Verify Posting Window Enforcement
To verify that incoming transfers are strictly rejected while the posting window is closed:
1. Temporarily run during Phase 0 or verify the exception logic in [`CbsFundsTransferService.java#L104`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsFundsTransferService.java#L104).
2. Incoming transfers return:
   ```json
   {
     "status": 500,
     "message": "CBS Posting window is closed. Business status: EOD_CUTOFF"
   }
   ```

#### Step 5: Database Table Verification Steps (Oracle XE & PostgreSQL)
1. **Check `COB_BATCH_LOG` Table (Oracle XE)**:
   ```sql
   SELECT batch_id, business_date, accounts_processed, total_fees_collected, total_interest_accrued, status, started_at, completed_at 
   FROM cob_batch_log 
   ORDER BY started_at DESC FETCH FIRST 1 ROWS ONLY;
   ```
   *Expected Invariant*: Row shows `status = 'COMPLETED'` and batch progress details.

2. **Check `EOD_BALANCE_SNAPSHOTS` Table (Oracle XE)**:
   ```sql
   SELECT snapshot_id, business_date, account_id, closing_balance, frozen_at 
   FROM eod_balance_snapshots 
   ORDER BY frozen_at DESC FETCH FIRST 5 ROWS ONLY;
   ```
   *Expected Invariant*: Snapshot records frozen for each account representing immutable end-of-day ledgers.

3. **Check `INTEREST_ACCRUALS` Table (Oracle XE)**:
   ```sql
   SELECT accrual_id, accrual_date, account_id, daily_rate, accrued_amount, tax_withheld, net_accrual, is_capitalized 
   FROM interest_accruals 
   ORDER BY created_at DESC FETCH FIRST 5 ROWS ONLY;
   ```
   *Expected Invariant*: Accrued amount computed at 0.5% p.a. (`cleared_balance * 0.005 / days_in_year`), `tax_withheld = gross * 0.20`, `net_accrual = gross - tax`, and `is_capitalized = 0`.

4. **Check `SYSTEM_DATES` Table (Oracle XE)**:
   ```sql
   SELECT system_date_id, business_date, status, posting_window_open, updated_at 
   FROM system_dates;
   ```
   *Expected Invariant*: Exactly one active row `SYS-DATE-1` with `business_date` advanced to $T+1$, `status = 'ONLINE'`, and `posting_window_open = 1`.

---

### Test 2.2: Full Biometric Step-Up Verification & Settlement
Implemented in [`TransferOrchestratorController.java#L75-L143`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/TransferOrchestratorController.java#L75-L143).

#### Step 1: Initiate Transfer Exceeding ₱50,000 Threshold
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "1000-2000-3001",
    "destinationAccountId": "1000-2000-3002",
    "amount": 75000.00,
    "currency": "PHP",
    "description": "High Value Settlement",
    "deviceId": "DEVICE-SECURE-001"
  }'
```
*Response*:
```json
{
  "transactionId": "TXN-BIO-TEST",
  "status": "Authorized",
  "message": "BIOMETRIC_CHALLENGE_REQUIRED: Device biometric verification required for transaction.",
  "biometricRequired": true,
  "biometricChallenge": "CHALLENGE-TOKEN-XYZ-12345"
}
```
*Copy the `transactionId` and `biometricChallenge` token.*

#### Step 2: Complete Biometric Challenge-Response Verification
Submit the cryptographic assertion signature:
```bash
curl -X POST http://localhost:8080/api/v1/transfers/verify-biometric \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-BIO-TEST",
    "challengeToken": "CHALLENGE-TOKEN-XYZ-12345",
    "assertionSignature": "VALID_MOCK_ASSERTION_SIGNATURE",
    "deviceId": "DEVICE-SECURE-001",
    "destinationAccountId": "1000-2000-3002",
    "amount": 75000.00
  }'
```
*Expected Output*:
```json
{
  "transactionId": "TXN-BIO-TEST",
  "status": "Posted",
  "message": "Transfer executed successfully on CBS core",
  "biometricRequired": false
}
```
*Verification*: Check `1000-2000-3001` balance; exactly ₱75,000.00 has been debited.

---

### Test 2.3: Anti-Scam Cooling-Off Polling & Cancellation (Decoupled Redis Architecture)
Implemented in [`TransferOrchestratorController.java#L145-L165`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/TransferOrchestratorController.java#L145-L165), [`TransferOrchestrationService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/TransferOrchestrationService.java), and [`CoolOffService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/CoolOffService.java).

> **Architectural Note (Pure OFS & State Decoupling)**: 
> Temenos T24 CBS strictly enforces a pure Temenos OFS wire protocol (`text/plain`). To maintain architectural separation of concerns, the Transfer Orchestrator holds high-value cooling-off state (`amount >= 250,000.00`) exclusively in Redis under key `tx:cooloff:<transactionId>` with a 10-minute (600s) TTL. CBS is **never notified** of pending holds or cancellations, ensuring zero premature writes to Oracle XE `transactions` or `transaction_status_history` tables. Only when the cooling-off window finishes and the transfer is explicitly released does the Orchestrator submit the raw OFS message (`FUNDS.TRANSFER,INITIATE...`) to CBS.

#### Step 1: Initiate Transfer Exceeding ₱250,000 Threshold
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "1000-2000-3001",
    "destinationAccountId": "1000-2000-3002",
    "amount": 300000.00,
    "currency": "PHP",
    "description": "High value escrow transfer",
    "biometricSignature": "MOCK_DEVICE_SIGNATURE"
  }'
```
*Response*:
```json
{
  "transactionId": "TXN-COOL-101",
  "status": "Reserved",
  "coolingOffRequired": true,
  "coolingOffExpiresInSeconds": 600,
  "message": "COOLING_OFF_PERIOD_INITIATED: High-value transaction locked for 10 minutes to protect against fraud."
}
```

#### Step 2: Poll Remaining Cooling-Off Window
```bash
curl http://localhost:8080/api/v1/transfers/TXN-COOL-101/cool-off
```
*Expected Output*:
```json
{
  "transactionId": "TXN-COOL-101",
  "isCoolingOff": true,
  "remainingSeconds": 592
}
```

#### Step 3: Inspect Redis Cache State (Active Hold)
```bash
docker exec -i redis redis-cli GET tx:cooloff:TXN-COOL-101
docker exec -i redis redis-cli TTL tx:cooloff:TXN-COOL-101
```
*Expected Invariant*:
* Returns the complete serialized JSON request payload.
* TTL returns remaining seconds ($\le 600$).

#### Step 4: Verify Oracle XE Isolation (Zero Pre-Posting Mutation)
```sql
-- Balances in Oracle XE are NOT debited or frozen:
SELECT account_id, balance_amount, hold_amount, available_balance 
FROM balance_master 
WHERE account_id = '1000-2000-3001';

-- Zero transaction records in CBS Master DB:
SELECT COUNT(*) FROM transactions WHERE transaction_id = 'TXN-COOL-101';

-- Zero status history transitions in CBS:
SELECT COUNT(*) FROM transaction_status_history WHERE transaction_id = 'TXN-COOL-101';
```
*Expected Invariant*: Count is `0`. Pure OFS decoupling ensures CBS has no record of the transaction during the cooling-off hold.

#### Step 5: Cancel Transfer During Cooling-Off Period
```bash
curl -X POST http://localhost:8080/api/v1/transfers/cancel \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-COOL-101"
  }'
```
*Expected Output*:
```json
{
  "transactionId": "TXN-COOL-101",
  "cancelled": true,
  "message": "Transfer cancelled successfully during cooling-off window."
}
```

#### Step 6: Post-Cancellation Verification
1. **Redis Key Eviction**:
   ```bash
   docker exec -i redis redis-cli GET tx:cooloff:TXN-COOL-101
   ```
   *Returns*: `(nil)`. The hold key was deleted immediately.
2. **Polling API Verification**:
   ```bash
   curl http://localhost:8080/api/v1/transfers/TXN-COOL-101/cool-off
   ```
   *Returns*: `{"transactionId":"TXN-COOL-101","isCoolingOff":false,"remainingSeconds":0}`.
3. **Database Ledger Cleanliness**:
   ```sql
   SELECT COUNT(*) FROM transactions WHERE transaction_id = 'TXN-COOL-101';
   ```
   *Returns*: `0`. The transaction never touched CBS or mutated customer balances.

---

### Test 2.4: Scam Advisory Warning Interception & User Acknowledgment
Implemented in [`TransferOrchestrationService.java#L76-L92`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/TransferOrchestrationService.java#L76-L92).

#### Step 1: Submit Transfer Triggering Risk Advisory Without Acknowledgment
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "1000-2000-3001",
    "destinationAccountId": "1000-2000-3002",
    "amount": 5000.00,
    "currency": "PHP",
    "description": "Urgent investment transfer to crypto broker",
    "scamAdvisoryAcknowledged": false
  }'
```
*Expected Output*:
```json
{
  "status": "Processing",
  "message": "SCAM_ADVISORY_WARNING: Potential high-risk payee detected. ... Please confirm acknowledgment."
}
```

#### Step 2: Resubmit with Explicit Customer Acknowledgment
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "1000-2000-3001",
    "destinationAccountId": "1000-2000-3002",
    "amount": 5000.00,
    "currency": "PHP",
    "description": "Urgent investment transfer to crypto broker",
    "scamAdvisoryAcknowledged": true
  }'
```
*Expected Output*: The transaction bypasses the advisory hold and settles with `status: "Posted"`.

---

### Test 2.5: Beneficiary Insolvency During Reversal
Implemented in [`CbsReversalService.java#L166-L169`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsReversalService.java#L166-L169).

1. Execute a transfer of ₱20,000.00 from `1000-2000-3001` to `1000-2000-3005`.
2. As a Teller Maker, create a dispute ticket:
   ```bash
   curl -X POST http://localhost:8080/api/v1/reversals/request \
     -H "Content-Type: application/json" \
     -d '{
       "originalTransactionId": "<TRANSACTION_ID>",
       "makerId": "usr-1003-tel-001",
       "reason": "CUSTOMER_DISPUTE"
     }'
   ```
   Save the returned `ticketId`.
3. In a separate operation, drain `1000-2000-3005` so its available balance is less than ₱20,000.00 (e.g. transfer all its funds to `1000-2000-3002`).
4. Attempt Checker approval:
   ```bash
   curl -X POST http://localhost:8080/api/v1/reversals/approve \
     -H "Content-Type: application/json" \
     -d '{
       "reversalRequestId": "<TICKET_ID>",
       "checkerId": "usr-1004-adm-001",
       "checkerNotes": "Approved"
     }'
   ```
*Expected Output*: HTTP 400/500 Bad Request with explicit message:
```text
Beneficiary account has insufficient funds to process reversal: available=..., required=20000.00
```
*Verification*: Dual-control reversal enforces that clawbacks cannot put an account into an unauthorized overdraft.

---

### Test 2.6: Account Lifecycle & Security Freezing
Implemented in [`AccountController.java#L48-L55`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/account-service/src/main/java/com/fse/banking/account/controller/AccountController.java#L48-L55).

*Note*: In the core banking domain, valid `AccountStatus` enum values are `ACTIVE`, `LOCKED`, `PENDING_APPROVAL`, `SUSPENDED`, and `CLOSED`. Security freezing corresponds to status `LOCKED`.

#### Step 1: Freeze / Lock an Account via Admin API
```bash
curl -X PATCH http://localhost:8080/api/v1/accounts/1000-2000-3002/status \
  -H "Content-Type: application/json" \
  -d '{
    "status": "LOCKED"
  }'
```
*Expected Output*: Returns account object with `"status": "LOCKED"`.

#### Step 2: Test via Admin Portal
1. Open `http://localhost:3000/admin`.
2. Go to **Customer 360 & Account Management**.
3. Locate `1000-2000-3002` and toggle the account status between `ACTIVE`, `LOCKED`, and `SUSPENDED`.
4. Attempting to initiate transfers on a locked account validates that the perimeter security blocks transactions on restricted accounts.

---

### Test 2.7: User Authentication, Refresh Token Rotation (RTR) & Token Reuse Detection
Implemented in [`AuthController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/account-service/src/main/java/com/fse/banking/account/controller/AuthController.java) and [`TokenRotationService.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/account-service/src/main/java/com/fse/banking/account/service/TokenRotationService.java).

#### Step 1: User Login
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -i \
  -d '{
    "email": "juan.dc@email.com",
    "password": "password123",
    "deviceType": "WEB"
  }'
```
*Verification*: If initial login requires 2FA MFA, response returns `{"status":"MFA_REQUIRED"}` and an OTP is dispatched to MailHog (`http://localhost:8025`). Verify the OTP via `POST /api/v1/auth/verify-login-otp` with `{"email":"juan.dc@email.com","otp":"<OTP_FROM_MAILHOG>"}` to receive the JWT access token and `Set-Cookie: refresh_token=...; HttpOnly; SameSite=Strict`.

#### Step 2: Refresh Token Rotation
```bash
curl -X POST http://localhost:8080/api/v1/auth/refresh \
  --cookie "refresh_token=<EXTRACTED_COOKIE_VALUE>"
```
*Verification*: Returns a new access token and issues a **new** rotated refresh token cookie.

#### Step 3: Token Reuse Detection (Security Invariant)
Re-submit the **old/previous** refresh token:
```bash
curl -X POST http://localhost:8080/api/v1/auth/refresh \
  --cookie "refresh_token=<OLD_USED_COOKIE_VALUE>"
```
*Expected Output*: HTTP 401 Unauthorized with Token Reuse Detected. The entire token family is immediately revoked in Redis.

---

### Test 2.8: Customer KYC Review & Approval
Implemented in [`KycController.java`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/account-service/src/main/java/com/fse/banking/account/controller/KycController.java).

#### Step 1: List Pending KYC Applications
```bash
curl http://localhost:8080/api/v1/kyc/pending
```
*Verification*: Returns JSON list of users in `kyc_status: "PENDING"`, including `usr-1001-cst-001`.

#### Step 2: Approve Customer KYC
```bash
curl -X POST http://localhost:8080/api/v1/kyc/usr-1001-cst-001/approve
```
*Verification*: KYC profile status updates to `VERIFIED` with `{"status":"ACTIVE","kyc_status":"VERIFIED"}`.

---

### Test 2.9: AI/ML Risk Engine & Geo-Velocity Simulation
Implemented in [`main.py`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/risk-service/app/main.py) and [`geo_math.py`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/risk-service/app/geo_math.py).

#### Step 1: Test Geo-Velocity (Impossible Travel Speed)
Navigate to **`http://localhost:3000/admin-executive`**:
1. Open the **Customer Geo Simulator**.
2. Select baseline origin: `Manila, Philippines`.
3. Select new device origin: `London, United Kingdom` with an interval of `5 minutes`.
4. Run evaluation.
*Verification*: The engine computes velocity > 800 km/h, flags an impossible travel anomaly, elevates risk score to `95`, and requires biometric step-up or outright `BLOCK`.

#### Step 2: Test NLP Social Engineering Memo Detection via API
```bash
curl -X POST http://localhost:8084/risk/memo-check \
  -H "Content-Type: application/json" \
  -d '{
    "memo": "immediate police bail fee transfer do not inform family"
  }'
```
*Verification*: The Qwen NLP model scores social engineering coercion, flags `POLICE_IMPERSONATION_SCAM`, and returns advisory warning recommendation.

---

### Test 2.10: Regulatory Compliance Artifact Generation (COB-Triggered) & Customer PDF Statements
Implemented in [`ComplianceKafkaConsumer.java#L77-L94`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/consumer/ComplianceKafkaConsumer.java#L77-L94) and [`ComplianceController.java#L116-L158`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/backend/compliance-service/src/main/java/com/bank/compliance/controller/ComplianceController.java#L116-L158).

#### Step 1: Automated EOD Regulatory Artifact Generation (Triggered via COB)
*Architecture Note*: In production compliance architecture, regulatory compliance filings are generated strictly when the Close of Business (COB) batch completes.
1. When `POST /api/v1/cbs/cob/run` executes Phase 3 (Snapshot), it publishes a `BalanceSnapshotFrozenEvent` to Kafka topic `banking.batch.events`.
2. `ComplianceKafkaConsumer` automatically intercepts the event and generates three immutable regulatory artifacts uploaded directly to Azurite Blob Storage:
   * **GL Trial Balance Spreadsheet**: `eod/gl-trial-balance-<YYYY-MM-DD>.xlsx`
   * **GL EOD Reconciliation Audit Report**: `eod/gl_eod_reconciliation_<YYYY-MM-DD>.pdf`
   * **BIR Form 2306 Withholding Certificate**: `eod/bir-2306-<YYYY-MM-DD>.pdf`
3. Each artifact's SHA-256 checksum, URI, and record count are automatically registered in the CBS audit vault (`/api/v1/cbs/audit/compliance-filings`).

#### Step 2: On-Demand Customer Statement PDF Generation
To download an individual account statement on demand:
```bash
curl http://localhost:8080/api/v1/compliance/statements/1000-2000-3001/pdf --output statement-3001.pdf
```
*Verification*:
* Open `statement-3001.pdf`.
* Confirm the PDF is formatted with Aura Bank branding, customer account number, opening/closing balance, and ledger mutations table.

---

### Test 2.11: Real-Time Event & Alert Verification (MailHog, Kafka UI, Redis Insight)

#### A. Email Receipts & 2FA OTPs (MailHog)
1. Open **`http://localhost:8025`** in your browser.
2. Complete any transfer or OTP verification.
3. Verify that transactional confirmation emails and OTP codes arrive in MailHog instantly.

#### B. Apache Kafka Stream Inspection (Kafka UI)
1. Open **`http://localhost:8089`** in your browser.
2. Inspect topics:
   * **`banking.transfers.events`**: Contains `TransferExecutedEvent` with transaction ID and debit/credit amounts.
   * **`banking.transfers.dlq`**: Contains dead-lettered failure events when circuit breakers trip.
   * **`banking.batch.events`**: Contains `BalanceSnapshotFrozenEvent` and `EodCompletedEvent` emitted during COB batch runs.

#### C. Redis Distributed Locks & Cache (Redis Insight)
1. Open **`http://localhost:5540`** in your browser.
2. Browse active keys:
   * `tx:cooloff:*`: Active 10-minute high-value transfer hold payloads (decoupled from CBS until settlement).
   * `idemp:*`: Distributed idempotency locks with TTL.
   * `account:balance:*`: Balance cache keys (evicted on mutation to guarantee strict read consistency).
   * `blacklist:*`: Revoked JWT tokens after user logout.

---

## 4. End-to-End Test Matrix & Verification Checklist

| Test Item | Verification Tool / Command | Pass Condition |
| :--- | :--- | :--- |
| **Intra-bank Transfer** | T24 Lab (`/t24-test`) Tab 1 | Working balance decrements by exact amount; GL entries committed. |
| **Transaction History** | T24 Lab (`/t24-test`) Tab 2 | `ENQUIRY.SELECT` table lists transaction; Audit modal displays lifecycle. |
| **Status Lifecycle Audit**| T24 Lab Tab 1/2/3 / CBS OFS | Full transitions recorded (`Initiated`➔`Authorized`➔`Reserved`➔`Processing`➔`Posted`). |
| **Four-Eyes Reversal** | T24 Lab (`/t24-test`) Tab 3 | Maker tickets status `PendingReversal`; independent Checker approves and refunds. |
| **Direct Saga Reversal** | T24 Lab (`/t24-test`) Tab 3 | Automated compensation rollback completes via raw OFS without human queue. |
| **DLQ Replay** | T24 Lab (`/t24-test`) Tab 4 | Simulated error enqueued; clicking Replay resolves incident in audit vault. |
| **Overdraft Rejection** | T24 Lab (`/t24-test`) Tab 5 | ₱999M transfer rejected with HTTP 400 Insufficient Funds; zero balance change. |
| **Circular Transfer Block**| T24 Lab (`/t24-test`) Tab 5 | Same source & destination rejected with HTTP 400. |
| **Cooling-Off Interception**| T24 Lab Tab 5 / API | ₱300k transfer held in Redis (`tx:cooloff:*`) with 600s timer; zero writes to CBS. |
| **Biometric Challenge** | T24 Lab Tab 5 / API | ₱75k transfer paused in `Authorized` status with challenge token. |
| **COB/EOD Batch Processing**| `POST /api/v1/cbs/cob/run` | ADB fee deducted, interest accrued, GL reconciled, date advances to $T+1$. |
| **Biometric Settlement** | `POST /verify-biometric` | Submitting signature settles transfer to CBS core with `status: Posted`. |
| **Cooling-Off Cancellation**| `POST /transfers/cancel` | Transfer cancelled; Redis key evicted immediately; funds never touched. |
| **Scam Advisory Bypass** | `POST /transfers` with ack | Supplying `scamAdvisoryAcknowledged: true` overrides advisory warning. |
| **Beneficiary Insolvency** | `POST /reversals/approve` | Reversal blocked if beneficiary account lacks available funds for debit. |
| **Account Freezing** | Admin Portal / `PATCH /status`| Account status set to `LOCKED`; subsequent transactions blocked. |
| **Token Rotation & Reuse** | `POST /auth/refresh` | Valid refresh rotates cookie; reused old refresh token revokes session family. |
| **KYC Approval** | Admin Portal / `POST /kyc` | Pending customer profile reviewed and marked `VERIFIED`. |
| **Geo-Velocity Anomaly** | Admin Executive (`/admin-executive`)| Manila-to-London travel in 5 mins triggers > 800 km/h fraud block. |
| **PDF Bank Statement** | `GET /statements/{id}/pdf` | Downloads valid PDF with account statement and audit ledger trail. |
| **Email & OTP Delivery** | MailHog (`:8025`) | Confirmation emails and OTP codes visible in SMTP web inbox. |
| **Event Streaming** | Kafka UI (`:8089`) | Events appear on `banking.transfers.events` and `banking.batch.events`. |
