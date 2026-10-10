# Complete Banking Logic & T24 Core Testing Instructions Runbook

This testing runbook provides rigorous, step-by-step instructions to test, validate, and verify all banking logic implemented across Aura Bank's microservices architecture. It focuses strictly on the **actual implementation on disk**, covering both the unified staff console (`http://localhost:3000`), the embedded T24 Core Banking console (`/cbs`), and direct backend API / database verification.

---

## 1. System Architecture & Testing Environment Reference

### A. Microservices & Network Topology

```
                                  [ Web Browser / Client ]
                                             |
                                  (HTTP :3000 / Vite Dev)
                                             |
                                   [ API Gateway :8080 ]
                  ___________________________|___________________________
                 |               |           |            |              |
          :8081  |        :8082  |    :8083  |     :8084  |       :8086  |
       [Account Service]  [Transfer   [Notification [Risk Service [Compliance
                           Orchestrator]  Service]   (FastAPI/AI)]   Service]
                                 |                                       |
                   (Private OFS) |                                       | (Internal Audit)
                                 v                                       v
                     [ T24 Mock Core Banking System (CBS) :8085 ]
                                 |                       |
                     (JDBC / Oracle XE 21c)   (JDBC / PostgreSQL 15)
                                 v                       v
                     [ Oracle XE Master DB ]  [ PostgreSQL Audit Vault ]
                            (:1521)                   (:5432)
```

| Component / Service | Technology | Port | Access Type | Primary Responsibility & Endpoints |
| :--- | :--- | :--- | :--- | :--- |
| **Frontend Staff Console** | React 18 / Vite / Tailwind | `3000` | Public Browser | Unified back-office portal (`/`, `/customers`, `/kyc`, `/transfers`, `/reversals`, `/sar`, `/geo`, `/audit`, `/cbs`, `/drive`) |
| **API Gateway** | Spring Cloud Gateway | `8080` | Public Perimeter | Perimeter routing, JWT authentication, rate limiting, path rewrites |
| **Account Service** | Spring Boot | `8081` | Via Gateway | User management, staff authentication, KYC reviews, account provisioning (`/api/v1/auth`, `/api/v1/kyc`, `/api/v1/accounts`, `/api/v1/users`) |
| **Transfer Orchestrator** | Spring Boot | `8082` | Via Gateway | Transfer sagas, idempotency, cooling-off, biometric challenges, maker-checker reversals (`/api/v1/transfers`, `/api/v1/reversals`) |
| **Notification Service** | Spring Boot | `8083` | Via Gateway | Transaction email receipts, Server-Sent Events (SSE) notification stream, SMS/OTP simulation (`/api/v1/notifications`) |
| **Risk Service (NanoJev)** | FastAPI (Python) | `8084` | Via Gateway | Two-stage fraud scoring, geo-velocity evaluation, NLP memo checks, SAR registry (`/risk/decision`, `/risk/memo-check`, `/sar`) |
| **T24 Mock CBS** | Spring Boot | `8085` | **Internal Network Only** | **Sole custodian of banking business logic**: Master Oracle DB balance mutations, Temenos OFS parser, double-entry General Ledger, Close of Business (COB) EOD batch, PostgreSQL immutable audit vault (`/api/v1/cbs/**`) |
| **Compliance Service** | Spring Boot | `8086` | Via Gateway | Customer PDF statement generation, Azurite blob storage operations, DLQ management, proxy to CBS audit vault (`/api/v1/compliance/**`, `/api/v1/ledger/audit`) |
| **MailHog SMTP Web UI** | Go Mock SMTP | `8025` | Public Browser | Real-time web inbox for transaction receipts and MFA login OTP emails (`http://localhost:8025`) |
| **Kafka Dashboard** | Kafka UI | `8089` | Public Browser | Real-time Kafka topic and message inspection (`http://localhost:8089`) |
| **Redis GUI** | Redis Insight | `5540` | Public Browser | Visual browser for distributed idempotency keys and cooling-off holds (`http://localhost:5540`) |
| **Azurite Blob Storage** | Azure Storage Mock | `10000` | Internal/Client | Azure Blob Storage endpoint for EOD spreadsheets, statements, and audit reports (`devstoreaccount1`) |
| **Adminer Database GUI** | Web DB Client | `8088` | Public Browser | Web interface for querying Oracle XE (`:1521`) and PostgreSQL (`:5432`) (`http://localhost:8088`) |

> **Perimeter Security Invariant**: 
> In accordance with zero-trust banking architecture, `t24-mock-cbs` (`:8085`) is **never directly exposed to public callers or frontends** through the Gateway. External clients communicate strictly via `transfer-orchestrator` (`:8082`), which transforms requests into Temenos OFS wire messages and communicates internally with `t24-mock-cbs`. Direct calls to `http://localhost:8085` are reserved for internal batch jobs (such as COB) or local development diagnosis.

---

### B. Pre-Seeded Accounts & Staff Personas

#### 1. Core Operating Accounts (Oracle XE Master DB)
* `1000-2000-3001`: Primary Savings Account (Starting Balance: ₱25,000,000.00)
* `1000-2000-3002`: Secondary Savings Account (Starting Balance: ₱5,000,000.00)
* `1000-2000-3004`: Tertiary Savings Account (Starting Balance: ₱5,200,000.00)
* `1000-2000-3005`: Quaternary Savings Account (Starting Balance: ₱3,750,000.00)

#### 2. Staff Personas & Login Credentials
Login page: **`http://localhost:3000`**

| Persona | Email | Password | Role | Permissions |
| :--- | :--- | :--- | :--- | :--- |
| **Diana Vance (Administrator)** | `diana.admin@bank.com` | `password123` | `ADMIN` | All portals: Overview, Customers, KYC, Transfers, Reversals, SAR/STR, Geo Simulator, Audit Trail, T24 Core Banking, Compliance Drive |
| **Crisostomo Ibarra (Teller Maker)** | `crisostomo.teller@bank.com` | `password123` | `TELLER` | Branch Operations: Overview, Customers, KYC, Transfers, Reversals (Maker file only), T24 Core Banking, Compliance Drive |
| **Beatriz Ocampo (Teller)** | `beatriz.teller@bank.com` | `password123` | `TELLER` | Branch Operations |
| **Carlos Mendoza (Manager Checker)** | `carlos.teller@bank.com` | `password123` | `MANAGER` | Branch Operations + Reversal Checker approvals |
| **Alex Rivera (Security Admin)** | `alex.admin@bank.com` | `password123` | `ADMIN` | Full administrative, compliance, and fraud desk access |
| **Team Member Accounts** | `wax@bank.com`, `hans@bank.com`, `zel@bank.com`, `maye@bank.com` (`ADMIN`)<br>`jm@bank.com`, `jessy@bank.com`, `angel@bank.com` (`TELLER`) | `password123` | `ADMIN` / `TELLER` | Full testing capabilities matching their designated role |

---

### C. Database Query Quick Reference

#### Method A: Command Line via Docker CLI
* **Oracle XE Master Database** (Accounts, Balances, Transactions, GL, Reversals):
  ```powershell
  docker exec -i oracle-xe-master sqlplus -s fse_user/fse_password@//localhost:1521/XEPDB1
  ```
  *(Enter SQL queries followed by a semicolon `;` and type `exit;` when done).*

* **PostgreSQL Dedicated Audit Vault** (Append-only immutable audit trail and DLQ failure incidents):
  ```powershell
  docker exec -i postgres-audit-vault psql -U audit_user -d banking_audit
  ```
  *(Enter SQL queries followed by a semicolon `;` and type `\q` when done).*

#### Method B: Adminer Web Management GUI (`http://localhost:8088`)
* **Oracle XE Master**: System: `Oracle`, Server: `oracle-xe-master:1521/XEPDB1`, Username: `fse_user`, Password: `fse_password`, Database: `XEPDB1`.
* **PostgreSQL Audit Vault**: System: `PostgreSQL`, Server: `postgres-audit-vault`, Username: `audit_user`, Password: `audit_password`, Database: `banking_audit`.

---

## 2. PART 1: Testing via the Unified Staff Console (`http://localhost:3000`)

Sign in with `diana.admin@bank.com` and `password123`.

---

### Test 1.1: T24 Core Banking Console — Intra-Bank Funds Transfer (`FUNDS.TRANSFER`)
* **Navigation**: Click **"T24 Core Banking"** in the sidebar navigation (`/cbs` or `/t24-test`).
* **Objective**: Verify standard funds transfer execution from Orchestrator JSON to Temenos OFS, atomic balance mutation, double-entry General Ledger posting, and 4-stage lifecycle recording.
* **Steps**:
  1. In the **Select Test Account** card at the top, select `1000-2000-3001`.
  2. Note the initial **Working Balance** and **Available to Spend**.
  3. Under the **"1. Funds Transfer"** tab:
     * **Source Account**: `1000-2000-3001`
     * **Destination Account**: `1000-2000-3002`
     * **Amount**: `5000.00`
     * **Currency**: `PHP`
     * **Description**: `Test Transfer via CBS Console`
  4. Click **"Execute Funds Transfer (FUNDS.TRANSFER)"**.
* **Frontend Verification Checkpoints**:
  * A green toast appears: *"Funds Transfer executed and posted via Gateway ➔ Orchestrator ➔ T24 CBS!"*.
  * The **Working Balance** of `1000-2000-3001` decreases by exactly ₱5,000.00.
  * In the **Execution Evidence & OFS Raw Logs** card:
    * Outbound payload contains generated `TXN.ID` (e.g. `TXN-582910`) and `DEBIT.ACCT.NO=1000-2000-3001`.
    * Inbound CBS response indicates `STATUS=SUCCESS` and status code `1`.
  * The generated Transaction ID (e.g., `TXN-582910`) is automatically populated into the Reversal form.
  * Under the Execution Evidence card, click **"Inspect Status Lifecycle History"**:
    * Modal opens and displays all 4 lifecycle transitions: `Initiated` ➔ `Authorized` ➔ `Reserved` ➔ `Processing` ➔ `Posted`.

* **Database Verification Steps**:
  1. **Check `BALANCE_MASTER` (Oracle XE)**:
     ```sql
     SELECT account_id, balance_amount, hold_amount, available_balance, updated_at 
     FROM balance_master 
     WHERE account_id IN ('1000-2000-3001', '1000-2000-3002');
     ```
     * `1000-2000-3001` (Sender): `balance_amount` reduced by `5000.0000`.
     * `1000-2000-3002` (Receiver): `balance_amount` increased by `5000.0000`.
     * Virtual column `available_balance` equals `balance_amount - hold_amount`.

  2. **Check `TRANSACTIONS` (Oracle XE)**:
     ```sql
     SELECT transaction_id, from_account_id, to_account_id, amount, currency, transaction_type, status, memo 
     FROM transactions 
     WHERE transaction_id = '<TXN_ID>';
     ```
     * Record exists with `status = 'Posted'`, `transaction_type = 'INTRA_BANK'`, `amount = 5000.0000`.

  3. **Check `GL_LEDGER` (Oracle XE - Double-Entry Accounting)**:
     ```sql
     SELECT journal_id, transaction_id, gl_code, debit_amount, credit_amount 
     FROM gl_ledger 
     WHERE transaction_id = '<TXN_ID>';
     ```
     * Exactly two balanced journal rows for Customer Demand Deposits (`GL-20100`):
       * **Debit leg**: `gl_code = '20100'`, `debit_amount = 5000.0000`, `credit_amount = 0.0000` (sender deduction).
       * **Credit leg**: `gl_code = '20100'`, `debit_amount = 0.0000`, `credit_amount = 5000.0000` (beneficiary addition).
       * Total debits equals total credits (`SUM(debit_amount) == SUM(credit_amount)`).

  4. **Check `TRANSACTION_STATUS_HISTORY` (Oracle XE - Full Audit Progression)**:
     ```sql
     SELECT history_id, transaction_id, from_status, to_status, change_reason, actor_id, actor_type 
     FROM transaction_status_history 
     WHERE transaction_id = '<TXN_ID>' 
     ORDER BY changed_at ASC;
     ```
     * 4 chronological records:
       1. `Initiated` ➔ `Authorized` (`change_reason = 'BIOMETRIC_AUTH_VERIFIED'`)
       2. `Authorized` ➔ `Reserved` (`change_reason = 'FUNDS_RESERVATION_EARMARKED'`)
       3. `Reserved` ➔ `Processing` (`change_reason = 'CBS_OFS_PROCESSING'`)
       4. `Processing` ➔ `Posted` (`change_reason = 'ACID_LEDGER_COMMITTED'`)

  5. **Check `ledger_mutation_audit` (PostgreSQL Audit Vault)**:
     ```sql
     SELECT audit_id, transaction_id, account_id, mutation_type, mutation_amount, before_balance, after_balance, status, sha256_hash 
     FROM ledger_mutation_audit 
     WHERE transaction_id = '<TXN_ID>';
     ```
     * Two append-only records logged (`DEBIT` on source, `CREDIT` on destination), anchored by a 64-character SHA-256 hash.

---

### Test 1.2: Transaction & Audit History Enquiry (`ENQUIRY.SELECT`)
* **Navigation**: Click **"T24 Core Banking"** (`/cbs`) ➔ Tab **"2. Transaction Enquiry (ENQUIRY.SELECT)"**.
* **Steps**:
  1. Ensure enquiry account is set to `1000-2000-3001`.
  2. Click **"Execute Query"**.
* **Frontend Verification Checkpoints**:
  * Renders the transaction history table matching Temenos `ENQUIRY.SELECT`.
  * The transfer from Test 1.1 appears with `Amount: ₱5,000.00`, `Type: INTRA_BANK`, and `Status: POSTED`.
  * Click the **"History"** button on the row to view the modal lifecycle audit timeline.

---

### Test 1.3: Four-Eyes Maker-Checker Reversals (BSP Circular 982)
* **Navigation**: Available in either:
  * **Option A**: Click **"Reversals"** in the sidebar (`/reversals`)
  * **Option B**: Click **"T24 Core Banking"** (`/cbs`) ➔ Tab **"3. Reversal (Dual Control)"**
* **Objective**: Enforce dual-control segregation of duties: A Teller Maker files a dispute request, and an independent Admin/Manager Checker approves it.
* **Steps**:
  1. Under **Maker Reversal Request**:
     * **Original Transaction ID**: Enter the Transaction ID from Test 1.1 (e.g. `TXN-582910`).
     * **Maker ID**: `usr-1003-tel-001` (Crisostomo Ibarra - Teller)
     * **Dispute Reason**: Select `CUSTOMER_DISPUTE`.
     * **Notes**: `Customer disputed transaction`.
     * Click **"Submit Reversal Request"**.
  2. **Phase A Verification**:
     * Toast confirms *"Reversal ticket created via Orchestrator! Waiting for Checker approval."*.
     * In Oracle XE:
       ```sql
       SELECT ticket_id, original_tx_id, maker_id, checker_id, status FROM reversal_requests WHERE original_tx_id = '<ORIGINAL_TX_ID>';
       ```
       * Record exists with `status = 'PENDING'`, `maker_id = 'usr-1003-tel-001'`, `checker_id IS NULL`.
       * Original transaction in `transactions` transitions to `status = 'PendingReversal'`.
  3. Under **Checker Authorization**:
     * **Ticket ID**: Verify the generated ticket ID is present.
     * **Checker ID**: Ensure it is set to an independent user (`usr-1004-adm-001` — Diana Administrator, Admin).
     * Click **"Authorize Reversal (Approve)"**.
  4. **Phase B Verification**:
     * Toast confirms *"Reversal APPROVED via Orchestrator! Compensating GL entries posted and balances reversed."*.
     * The Working Balance of `1000-2000-3001` is refunded by ₱5,000.00.
     * The Working Balance of `1000-2000-3002` is debited back by ₱5,000.00.
     * In Oracle XE:
       ```sql
       -- 1. Check ticket:
       SELECT ticket_id, status, checker_id, reversal_tx_id, resolved_at FROM reversal_requests WHERE original_tx_id = '<ORIGINAL_TX_ID>';
       -- Expected: status = 'APPROVED', checker_id = 'usr-1004-adm-001'

       -- 2. Check original transaction:
       SELECT transaction_id, status FROM transactions WHERE transaction_id = '<ORIGINAL_TX_ID>';
       -- Expected: status = 'Reversed'

       -- 3. Check compensating transaction:
       SELECT transaction_id, from_account_id, to_account_id, amount, transaction_type, status, approved_by 
       FROM transactions WHERE transaction_id = '<REVERSAL_TX_ID>';
       -- Expected: status = 'Reversed', transaction_type = 'REVERSAL', from_account_id = '1000-2000-3002', to_account_id = '1000-2000-3001'
       ```
     * In PostgreSQL Audit Vault:
       ```sql
       SELECT audit_id, transaction_id, mutation_type, mutation_amount, status FROM ledger_mutation_audit WHERE transaction_id = '<REVERSAL_TX_ID>';
       ```
       * Record logged with `mutation_type = 'REVERSAL'` and `status = 'COMMITTED'`.

---

### Test 1.4: Direct Compensating Saga Reversals (System Machine-to-Machine)
* **Navigation**: Click **"T24 Core Banking"** (`/cbs`) ➔ Tab **"3. Reversal"** ➔ Card **"Direct Compensating Saga"**.
* **Objective**: Verify automated, zero-human-intervention compensating rollbacks orchestrated by the event saga when downstream failures occur.
* **Steps**:
  1. Execute a new transfer of ₱2,500.00 in Tab 1 (`1000-2000-3001` to `1000-2000-3002`).
  2. In Tab 3, under **Direct Compensating Saga**, enter the new Transaction ID.
  3. Click **"Execute Direct Compensating Rollback"**.
* **Verification Checkpoints**:
  * Toast confirms *"Orchestrator compensating saga reversal executed via Gateway!"*.
  * Both account balances are immediately restored without waiting for any human Maker-Checker queue.
  * In Oracle XE:
    ```sql
    SELECT transaction_id, transaction_type, status, requires_maker_checker, approved_by 
    FROM transactions 
    WHERE from_account_id = '1000-2000-3002' AND to_account_id = '1000-2000-3001' AND transaction_type = 'REVERSAL'
    ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY;
    ```
    * `status = 'Reversed'`, `requires_maker_checker = 0`, `approved_by IS NULL` (no human approver).
  * In `transaction_status_history`:
    * `actor_id = 'SYSTEM_SAGA'`, `actor_type = 'SYSTEM_ORCH'`.

---

### Test 1.5: Dead Letter Queue (DLQ) Incident Browsing & Replay
* **Navigation**: Click **"T24 Core Banking"** (`/cbs`) ➔ Tab **"4. DLQ Incident Replays"**.
* **Objective**: Verify failed transaction capture in the PostgreSQL audit vault and recovery via DLQ replay during core banking outages.
* **Steps**:
  1. In a terminal, temporarily pause the CBS container to simulate a core outage:
     ```powershell
     docker pause group3-t24-mock-cbs
     ```
  2. Dispatch a transfer via curl through the Gateway:
     ```powershell
     curl.exe -s -X POST http://localhost:8080/api/v1/transfers `
       -H "Content-Type: application/json" `
       -d '{\"transactionId\":\"TXN-OUTAGE-001\",\"sourceAccountId\":\"1000-2000-3001\",\"destinationAccountId\":\"1000-2000-3002\",\"amount\":5000.00,\"currency\":\"PHP\",\"description\":\"Outage Resilience Test\",\"idempotencyKey\":\"OUTAGE-001\",\"scamAdvisoryAcknowledged\":true}'
     ```
     * Circuit breaker opens and routes `TransferFailedToDlqEvent` to Kafka topic `banking.transfers.dlq`.
  3. Unpause the CBS container:
     ```powershell
     docker unpause group3-t24-mock-cbs
     ```
  4. In the T24 Console Tab 4, click **"Refresh Incidents"**:
     * Locate `TXN-OUTAGE-001` with `Circuit Breaker: OPEN` and `Status: PENDING_REPLAY`.
     * Click **"Replay Transaction"**.
* **Verification Checkpoints**:
  * Toast confirms *"DLQ transfer TXN-OUTAGE-001 replayed successfully!"*.
  * In PostgreSQL:
    ```sql
    SELECT incident_id, transaction_id, replay_status, resolved_at FROM failed_transaction_audit WHERE transaction_id = 'TXN-OUTAGE-001';
    ```
    * `replay_status = 'RESOLVED'`, `resolved_at` is populated.

---

### Test 1.6: Banking Failure Simulator (Pre-Configured Invariants)
* **Navigation**: Click **"T24 Core Banking"** (`/cbs`) ➔ Tab **"5. Banking Failure Simulator"**.
* **Scenarios to Test**:
  1. **Insufficient Funds (Solvency Check)**: Attempts ₱999,999,999.00 transfer. Rejected with HTTP 400 (`Insufficient funds`). Database balance remains completely untouched.
  2. **Circular Same-Account Transfer**: Source == Destination. Rejected with HTTP 400 (`Source and destination accounts must be different`).
  3. **Non-Existent Account**: Destination `ACC-INVALID-999-NOTFOUND`. Rejected with HTTP 400 (`Account balance not found`).
  4. **Anti-Scam Cooling-Off Period (BSP Circular 1140)**: ₱300,000.00 transfer. Status `Reserved`, held in Redis (`tx:cooloff:*`) with 600s timer; zero writes to CBS.
  5. **Strong Customer Authentication (SCA) Step-Up**: ₱75,000.00 transfer without biometric signature. Status `Authorized`, cryptographic challenge token issued.
  6. **Four-Eyes Dual-Control Violation**: Checker ID == Maker ID (`usr-1003-tel-001`). Rejected with HTTP 400 (`Checker ID cannot match Maker ID`).
  7. **Idempotency Duplicate Replay**: Re-sending identical `idempotencyKey`. Returns original posted result without double-debiting.
  8. **DLQ Incident Audit Vault**: Verifies PostgreSQL audit vault connectivity and replay pipeline.

---

### Test 1.7: Customer 360 & Account Security Freezing
* **Navigation**: Click **"Customers"** in the sidebar (`/customers`).
* **Objective**: View customer accounts, inspect balances, and enforce account locking / security freezing.
* **Steps**:
  1. Browse the customer list and locate `1000-2000-3002`.
  2. Click **"Lock Account"** (sets status to `LOCKED`).
  3. In Oracle XE:
     ```sql
     SELECT account_id, status FROM accounts WHERE account_id = '1000-2000-3002';
     ```
     * `status = 'LOCKED'`.
  4. Attempting to initiate transfers involving `1000-2000-3002` will be blocked at the perimeter.
  5. Click **"Unlock Account"** to restore it to `ACTIVE`.

---

### Test 1.8: Customer KYC Identity Maker-Checker Review
* **Navigation**: Click **"Identity review"** in the sidebar (`/kyc`).
* **Objective**: Review pending customer KYC registrations, inspect document photos, and approve or reject submissions.
* **Steps**:
  1. View pending identity verification queues.
  2. Select an application (e.g. `usr-1001-cst-001`).
  3. Inspect submitted ID document details and face match score.
  4. Click **"Approve Identity"**.
  5. In Oracle XE:
     ```sql
     SELECT user_id, status FROM users WHERE user_id = 'usr-1001-cst-001';
     ```
     * Status updates to `ACTIVE` and KYC status to `VERIFIED`.

---

### Test 1.9: Geo-Velocity Impossible Travel Simulator
* **Navigation**: Click **"Location simulator"** in the sidebar (`/geo`).
* **Objective**: Validate AI/ML fraud scoring on impossible physical travel speeds across consecutive transactions.
* **Steps**:
  1. The interactive Leaflet map displays Manila as the baseline device location.
  2. Select a target foreign location: **London, United Kingdom** or **San Francisco, USA**.
  3. Set time interval to **5 minutes**.
  4. Click **"Simulate Location Jump"**.
* **Verification Checkpoints**:
  * Geo-velocity calculation shows speed $> 800\text{ km/h}$.
  * Risk level elevates to **CRITICAL** / **HIGH RISK** (Score $\ge 90$).
  * Transfer requires biometric step-up authentication or is outright blocked.

---

### Test 1.10: AMLA Suspicious Activity Reports (SAR / STR)
* **Navigation**: Click **"SAR / STR"** in the sidebar (`/sar`).
* **Objective**: Inspect AMLA regulatory filing drafts generated from suspicious transaction velocity and structuring patterns.
* **Steps**:
  1. View the live SAR registry table populated by the Risk Service (`/sar`).
  2. Inspect flagged indicators (e.g., structuring amounts under ₱500,000 threshold, rapid fund movement).
  3. Click **"File with AMLC"** to mark a draft as filed.

---

### Test 1.11: Append-Only Immutable Audit Trail Journal
* **Navigation**: Click **"Audit trail"** in the sidebar (`/audit`).
* **Objective**: View the append-only ledger journal queried live from the PostgreSQL Immutable Audit Vault via Gateway $\rightarrow$ `compliance-service` $\rightarrow$ `t24-mock-cbs`.
* **Steps**:
  1. The table displays every historical debit, credit, hold, and reversal record with timestamps, account IDs, mutation amounts, resulting balances, actor IDs, and commit statuses.
  2. Use the search bar to filter by transaction ID (e.g. `TXN-582910`) or account ID (`1000-2000-3001`).
  3. Note that every record reflects the cryptographic SHA-256 chain persisted in PostgreSQL `ledger_mutation_audit`.

---

### Test 1.12: Compliance Drive — Azurite Blob Storage Explorer
* **Navigation**: Click **"Compliance Drive"** in the sidebar (`/drive` or `/azurite-drive`).
* **Objective**: Browse, upload, and download regulatory artifacts strictly from live Azure Blob Storage (`devstoreaccount1` / container `compliance`).
* **Clean State Guarantee**:
  * In a clean stack environment, zero dummy placeholder files are displayed.
  * Files appear only after being generated during the Close of Business (COB) batch run or uploaded as statements.
* **Features**:
  * Browse folders (`eod/`, `statements/`, `compliance/`).
  * Preview metadata: file size, MIME type, upload timestamp.
  * Download live files (`.xlsx`, `.pdf`).
  * Manual upload: Upload external audit documents directly to Azurite storage.

---

## 3. PART 2: Advanced Backend Banking Logic (Direct API & Batch Runbook)

---

### Test 2.1: Close of Business (COB) / EOD Batch Processing
Implemented in `CbsCobBatchService.java` and `CobController.java` inside `t24-mock-cbs`.

#### Step 1: Check System Business Date and Window Status
Run in terminal (PowerShell or Bash):
```powershell
curl.exe -s http://localhost:8085/api/v1/cbs/system-date
```
*Expected Output*:
```text
SYSTEM.DATE/I/PROCESS//SYS-DATE-1,BUSINESS.DATE:2026-10-09,STATUS:ONLINE,POSTING.WINDOW.OPEN:true
```

#### Step 2: Trigger Close of Business (COB) Batch Run
```powershell
curl.exe -X POST http://localhost:8085/api/v1/cbs/cob/run
```
*Expected Output*:
```text
COB.RUN/I/PROCESS//BATCH-...,ACCOUNTS.PROCESSED:4,FEES.COLLECTED:50.00,INTEREST.ACCRUED:...,STATUS:COMPLETED,MESSAGE:COB batch execution completed
```

#### Step 3: Verify the 5 Batch Execution Phases
1. **Phase 0 (Cutoff)**: The posting window transitions to `EOD_CUTOFF` and `POSTING.WINDOW.OPEN: false`. Any incoming funds transfer returns HTTP 500 (`CBS Posting window is closed. Business status: EOD_CUTOFF`).
2. **Phase 1 (ADB Fee Deduction)**: Accounts with Average Daily Balance (ADB) below ₱5,000.00 are debited a ₱50.00 monthly fee, credited to Fee Income `GL-4001`.
3. **Phase 2 (Daily Interest Accrual & BIR Withholding Tax)**: Computes 0.5% p.a. daily interest based on cleared balance, withholds 20% BIR tax, and records entries into `INTEREST_ACCRUALS` with `is_capitalized = 0`.
4. **Phase 3 (GL Reconciliation & Snapshot)**: Freezes immutable account balances into `EOD_BALANCE_SNAPSHOTS`. Emits `BalanceSnapshotFrozenEvent` to Kafka topic `banking.batch.events`, which triggers `compliance-service` to generate:
   * **GL Trial Balance Spreadsheet**: `eod/gl-trial-balance-<YYYY-MM-DD>.xlsx`
   * **GL EOD Reconciliation Audit Report**: `eod/gl_eod_reconciliation_<YYYY-MM-DD>.pdf`
   * **BIR Form 2306 Withholding Certificate**: `eod/bir-2306-<YYYY-MM-DD>.pdf`
5. **Phase 4 (Date Rollover)**: Business date advances to $T+1$ (`2026-10-10`), and the posting window re-opens (`ONLINE`).

#### Step 4: Verify in Compliance Drive (`/drive`)
Navigate to `http://localhost:3000/drive`: All 3 generated EOD files appear in the drive and are ready for instant download!

---

### Test 2.2: Full Biometric Step-Up Verification & Settlement
Implemented in `TransferOrchestratorController.java`.

#### Step 1: Initiate Transfer Exceeding ₱50,000 Threshold
```powershell
curl.exe -X POST http://localhost:8080/api/v1/transfers `
  -H "Content-Type: application/json" `
  -d '{\"sourceAccountId\":\"1000-2000-3001\",\"destinationAccountId\":\"1000-2000-3002\",\"amount\":75000.00,\"currency\":\"PHP\",\"description\":\"High Value Settlement\",\"deviceId\":\"DEVICE-SECURE-001\"}'
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

#### Step 2: Complete Biometric Challenge-Response Verification
```powershell
curl.exe -X POST http://localhost:8080/api/v1/transfers/verify-biometric `
  -H "Content-Type: application/json" `
  -d '{\"transactionId\":\"TXN-BIO-TEST\",\"challengeToken\":\"CHALLENGE-TOKEN-XYZ-12345\",\"assertionSignature\":\"VALID_MOCK_ASSERTION_SIGNATURE\",\"deviceId\":\"DEVICE-SECURE-001\",\"destinationAccountId\":\"1000-2000-3002\",\"amount\":75000.00}'
```
*Response*:
```json
{
  "transactionId": "TXN-BIO-TEST",
  "status": "Posted",
  "message": "Transfer executed successfully on CBS core",
  "biometricRequired": false
}
```
*Verification*: Check `1000-2000-3001` balance in Oracle XE; exactly ₱75,000.00 has been debited.

---

### Test 2.3: Anti-Scam Cooling-Off Polling & Cancellation (Decoupled Redis Architecture)
Implemented in `TransferOrchestrationService.java` and `CoolOffService.java`.

#### Step 1: Initiate Transfer Exceeding ₱250,000 Threshold
```powershell
curl.exe -X POST http://localhost:8080/api/v1/transfers `
  -H "Content-Type: application/json" `
  -d '{\"sourceAccountId\":\"1000-2000-3001\",\"destinationAccountId\":\"1000-2000-3002\",\"amount\":300000.00,\"currency\":\"PHP\",\"description\":\"High value escrow transfer\",\"biometricSignature\":\"MOCK_DEVICE_SIGNATURE\"}'
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
```powershell
curl.exe http://localhost:8080/api/v1/transfers/TXN-COOL-101/cool-off
```
*Response*:
```json
{
  "transactionId": "TXN-COOL-101",
  "isCoolingOff": true,
  "remainingSeconds": 592
}
```

#### Step 3: Inspect Redis & Oracle XE Isolation
```powershell
docker exec -i redis redis-cli GET tx:cooloff:TXN-COOL-101
docker exec -i redis redis-cli TTL tx:cooloff:TXN-COOL-101
```
* Redis holds the serialized payload with TTL $\le 600$.
* In Oracle XE:
  ```sql
  SELECT COUNT(*) FROM transactions WHERE transaction_id = 'TXN-COOL-101';
  ```
  * Returns `0`. CBS is completely decoupled and untouched during the cooling-off window.

#### Step 4: Cancel Transfer During Cooling-Off Period
```powershell
curl.exe -X POST http://localhost:8080/api/v1/transfers/cancel `
  -H "Content-Type: application/json" `
  -d '{\"transactionId\":\"TXN-COOL-101\"}'
```
*Response*:
```json
{
  "transactionId": "TXN-COOL-101",
  "cancelled": true,
  "message": "Transfer cancelled successfully during cooling-off window."
}
```
* The Redis key is deleted immediately (`(nil)`), and zero funds were ever deducted.

---

### Test 2.4: On-Demand Customer Statement PDF Generation
Implemented in `ComplianceController.java` (`:8086`).

Generate and download an official bank statement PDF for account `1000-2000-3001`:
```powershell
curl.exe http://localhost:8080/api/v1/compliance/statements/1000-2000-3001/pdf --output statement-3001.pdf
```
* Open `statement-3001.pdf` to inspect the formatted document with Aura Bank header, customer account information, opening balance, transaction ledger lines, and closing balance.
* In `/drive`, the generated statement is automatically uploaded under `statements/1000-2000-3001/statement-<date>.pdf`.

---

### Test 2.5: Email Receipts & 2FA OTP Delivery (MailHog)
Open **`http://localhost:8025`** in your browser.
* When users log in or perform high-value transactions, notification emails and 2FA OTP verification codes are dispatched to MailHog instantaneously.
* Verify sender `notifications@aurabank.ph` and email contents.

---

### Test 2.6: Real-Time Event Streaming (Kafka UI)
Open **`http://localhost:8089`** in your browser.
* Topic **`banking.transfers.events`**: Inspect `TransferExecutedEvent` records emitted on successful settlements.
* Topic **`banking.transfers.dlq`**: Inspect dead-lettered events when circuit breakers trip.
* Topic **`banking.batch.events`**: Inspect `BalanceSnapshotFrozenEvent` emitted during COB batch runs.

---

## 4. End-to-End Test Matrix & Verification Checklist

| Test Item | Verification Method / UI Path | Expected Result |
| :--- | :--- | :--- |
| **Intra-Bank Transfer** | Staff Console `/cbs` (Tab 1) | Balance debits by exact amount; OFS returns status 1; GL balanced in Oracle XE; SHA-256 hash in PostgreSQL. |
| **Status Lifecycle History** | Staff Console `/cbs` (Tab 1/2 modal) | 4 stages recorded (`Initiated` ➔ `Authorized` ➔ `Reserved` ➔ `Processing` ➔ `Posted`). |
| **Transaction Enquiry** | Staff Console `/cbs` (Tab 2) | Displays Temenos `ENQUIRY.SELECT` records matching Oracle XE `transactions`. |
| **Four-Eyes Reversal** | Staff Console `/reversals` or `/cbs` (Tab 3) | Maker files dispute; independent Checker approves; compensating transaction created with status `Reversed`; balances restored. |
| **Direct Saga Reversal** | Staff Console `/cbs` (Tab 3) | Automated machine-to-machine compensation rollback executes without human maker-checker queue. |
| **DLQ Incident Replay** | Staff Console `/cbs` (Tab 4) | Captured failure in PostgreSQL audit vault replayed and resolved through transfer orchestrator. |
| **Solvency Overdraft Block** | Staff Console `/cbs` (Tab 5 Scenario 1) | ₱999M transfer rejected with HTTP 400 Insufficient Funds; zero balance mutation. |
| **Circular Transfer Block** | Staff Console `/cbs` (Tab 5 Scenario 2) | Same source and destination rejected with HTTP 400. |
| **Cooling-Off Interception** | Staff Console `/cbs` (Tab 5) / `POST /transfers` | ₱300k transfer held in Redis (`tx:cooloff:*`) with 600s timer; zero writes to CBS. |
| **Biometric Challenge & Auth**| Staff Console `/cbs` (Tab 5) / API | ₱75k transfer paused with challenge token; settles upon signature verification. |
| **COB/EOD Batch Processing** | `POST http://localhost:8085/api/v1/cbs/cob/run` | ADB fee deducted, interest accrued, GL reconciled, date advances $T+1$, 3 EOD files uploaded to Azurite. |
| **Cooling-Off Cancellation** | `POST /transfers/cancel` | Transfer cancelled; Redis key evicted immediately; funds never touched. |
| **Account Locking / Freezing** | Staff Console `/customers` | Status toggled to `LOCKED`; subsequent transfer attempts blocked at perimeter. |
| **KYC Verification** | Staff Console `/kyc` | Pending identity application reviewed and approved by staff. |
| **Geo-Velocity Anomaly** | Staff Console `/geo` | Manila-to-London in 5 minutes flags $> 800\text{ km/h}$ impossible travel and high risk. |
| **AMLA SAR Filings** | Staff Console `/sar` | Suspicious transaction patterns listed for AMLC reporting. |
| **Immutable Audit Trail** | Staff Console `/audit` | Live PostgreSQL ledger journal displayed with SHA-256 integrity hashes. |
| **Compliance Drive** | Staff Console `/drive` | Browses live Azurite container `compliance`; zero dummy files; downloads COB reports and statements. |
| **Email & OTP Delivery** | MailHog (`:8025`) | Transaction receipts and login OTPs visible in SMTP web inbox. |
| **Kafka Event Streams** | Kafka UI (`:8089`) | Real-time messages visible on `banking.transfers.events` and `banking.batch.events`. |
