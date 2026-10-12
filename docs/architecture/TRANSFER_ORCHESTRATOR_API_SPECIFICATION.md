# Transfer Orchestrator API Specification & Temenos OFS Contracts

This specification defines the complete REST API contract for the **Transfer Orchestrator (`:8082`)**, including business logic descriptions, JSR-380 validation schemas, concrete JSON payloads, and the corresponding **Temenos OFS (Open Financial Service) syntax translations** sent to the **T24 Mock CBS (`:8085`)**.

---

## Architectural Role of Transfer Orchestrator

```
┌────────────────────────────────────────────────────────────────────────┐
│                        TRANSFER ORCHESTRATOR                           │
│                         (Spring Boot :8082)                            │
├────────────────────────────────────────────────────────────────────────┤
│ 1. Perimeter Validation & Idempotency Enforcement                      │
│ 2. Synchronous Real-Time Risk Engine Call (< 2ms, Python :8084)        │
│ 3. Dynamic 2FA OTP Coordination (Notification Service :8083)           │
│ 4. REST JSON ──► Temenos OFS Wire Syntax Translation                   │
│ 5. OFS Dispatch to T24 Mock CBS (:8085) via HTTP/OFS Wire              │
│ 6. OFS Wire Response ──► Client REST JSON Translation                  │
│ 7. Asynchronous Audit Telemetry Event Publishing (Kafka :9092)        │
└────────────────────────────────────────────────────────────────────────┘
```

> [!IMPORTANT]
> **Strict Isolation Invariant:** The Transfer Orchestrator does **NOT** maintain a direct database connection to the Master CBS Ledgers in Azure SQL. All balance inquiries, fund validations, row-locking mutations, fee deductions, and EOD journals are executed **exclusively** by the T24 CBS via OFS protocol.

---

## Table of Endpoints

| Category | Method | Endpoint URI | Description |
| :--- | :---: | :--- | :--- |
| **Funds Transfer** | `POST` | `/api/v1/orchestrator/transfers` | Initiate retail transfer with risk evaluation and T24 OFS settlement |
| **Funds Transfer** | `POST` | `/api/v1/orchestrator/transfers/verify-otp` | Verify 2FA OTP for high-value (>₱50k) or high-risk transfers |
| **Reversal** | `POST` | `/api/v1/orchestrator/transfers/{transactionId}/reverse` | Reverse a committed transfer (chargeback / dispute / correction) |
| **Amount Holds** | `POST` | `/api/v1/orchestrator/accounts/{accountId}/holds` | Place soft hold / fund reservation (`AC.LOCKED.EVENTS`) |
| **Amount Holds** | `DELETE`| `/api/v1/orchestrator/accounts/{accountId}/holds/{holdId}`| Release or cancel active hold without debiting |
| **Amount Holds** | `POST` | `/api/v1/orchestrator/accounts/{accountId}/holds/{holdId}/capture` | Convert reserved hold into final settled debit |
| **Retry & Failure**| `POST` | `/api/v1/orchestrator/transfers/{transactionId}/retry` | Idempotent replay of failed / interrupted transfer |
| **Retry & Failure**| `GET`  | `/api/v1/orchestrator/transfers/{transactionId}/status` | Interrogate CBS status to confirm if committed or safe to retry |
| **EOD Batch** | `POST` | `/api/v1/orchestrator/eod/interest-calculation` | EOD Subfeature 1: Daily interest accrual & capitalization |
| **EOD Batch** | `POST` | `/api/v1/orchestrator/eod/fees` | EOD Subfeature 2: Account maintenance & below-ADB service fees |
| **EOD Batch** | `POST` | `/api/v1/orchestrator/eod/reports` | EOD Subfeature 3: Daily Trial Balance, GL balancing & BSP reports |
| **EOD Batch** | `POST` | `/api/v1/orchestrator/eod/trigger` | Unified master trigger for all 3 EOD subfeatures in sequence |
| **EOD Batch** | `GET`  | `/api/v1/orchestrator/eod/status/{batchJobId}` | Interrogate batch job status and execution telemetry |
| **Interbank Switch**| `POST` | `/api/v1/orchestrator/external/transfers` | Outward remittance via InstaPay / PESONet clearing network |

---

## 1. Funds Transfer Endpoints

### 1.1 Initiate Funds Transfer
`POST /api/v1/orchestrator/transfers`

#### Description
Receives client funds transfer request. Validates idempotency key in Redis, invokes the Python Risk Engine (`:8084`) for real-time anomaly scoring (< 2ms), routes to 2FA if score triggers `REQUIRE_2FA` or amount > PHP 50,000.00. Translates the JSON payload to Temenos OFS syntax (`FUNDS.TRANSFER`) and posts to T24 Mock CBS (`:8085`).

#### Temenos OFS Wire Syntax Mapping
* **Request String Sent to T24:**
  ```text
  FUNDS.TRANSFER,AUTH/I/PROCESS/0/1,{initiatorUserId}/{companyCode}/1,{transactionId},DEBIT.ACCT.NO={accountId},CREDIT.ACCT.NO={targetAccountId},AMOUNT={mutationAmount},CURRENCY={currency},TRANSACTION.TYPE=AC
  ```
* **Expected Response String from T24:**
  ```text
  {transactionId}//1/SUCCESS,DEBIT.ACCT.NO:1:1={accountId},CREDIT.ACCT.NO:1:1={targetAccountId},AMOUNT:1:1={mutationAmount},FEE:1:1=15.00
  ```

#### Request Schema
* **Headers:**
  - `Content-Type`: `application/json`
  - `X-Idempotency-Key` *(Optional, String)*: Unique client UUID for replay prevention
* **Request Body:**

| Field | Type | Required | Description | Constraints |
| :--- | :---: | :---: | :--- | :--- |
| `transaction_id` | String | No | Unique transaction reference | Auto-generated if omitted (e.g. `TX-A1B2C3D4`) |
| `account_id` | String | Yes | Source debit account identifier | Non-blank (e.g. `1000-2000-3001`) |
| `target_account_id` | String | Yes | Target credit account identifier | Must be different from `account_id` |
| `mutation_amount` | Decimal | Yes | Transfer amount | Strictly positive, max 4 decimal places |
| `currency` | String | No | ISO 4217 Currency Code | Defaults to `PHP` |
| `initiator_user_id` | String | No | Initiating user / channel maker | Defaults to `U1001` |
| `memo` | String | No | Payment description / memo | Max 255 chars (evaluated by Risk Engine) |
| `latitude` | Decimal | No | Device GPS latitude | Used for impossible-travel risk score |
| `longitude` | Decimal | No | Device GPS longitude | Used for impossible-travel risk score |
| `ip_address` | String | No | Client IP address | Used for geo-velocity risk check |

```json
{
  "transaction_id": "TX-20261005-001",
  "account_id": "1000-2000-3001",
  "target_account_id": "1000-2000-3002",
  "mutation_amount": 1500.0000,
  "currency": "PHP",
  "initiator_user_id": "U1001",
  "memo": "Lunch reimbursement",
  "latitude": 14.5547,
  "longitude": 121.0244,
  "ip_address": "120.28.64.10"
}
```

#### Response Schema

##### A. Immediate Settlement (HTTP 200 OK)
Returned when risk is `ALLOW` and amount <= PHP 50,000.00.
```json
{
  "transaction_id": "TX-20261005-001",
  "status": "SETTLED",
  "account_id": "1000-2000-3001",
  "target_account_id": "1000-2000-3002",
  "mutation_amount": 1500.0000,
  "fee_amount": 15.0000,
  "currency": "PHP",
  "t24_reference": "FT26095A",
  "raw_ofs_response": "TX-20261005-001//1/SUCCESS,DEBIT.ACCT.NO:1:1=1000-2000-3001,AMOUNT:1:1=1500.00",
  "risk_score": 0.12,
  "risk_decision": "ALLOW",
  "settled_at": "2026-10-05T13:45:00.128Z"
}
```

##### B. 2FA Verification Required (HTTP 202 Accepted)
Returned when risk requires 2FA or amount > PHP 50,000.00. Funds soft-held pending OTP.
```json
{
  "transaction_id": "TX-20261005-002",
  "status": "PENDING_2FA_VERIFICATION",
  "account_id": "1000-2000-3001",
  "mutation_amount": 75000.0000,
  "message": "Dynamic 2FA triggered. One-time passcode dispatched via Notification Service.",
  "otp_expires_in_seconds": 300,
  "requires_otp": true
}
```

##### C. Error: T24 Rejection (HTTP 422 Unprocessable Entity)
Returned when source/target is frozen or funds are insufficient.
```json
{
  "status": "T24_REJECTED",
  "error_code": "T24_AUTHORIZATION_FAILED",
  "t24_transaction_id": "TX-20261005-003",
  "message": "Account 1000-2000-9001 IS FROZEN - Debit not permitted",
  "raw_ofs_response": "TX-20261005-003//-1/NO,ACCOUNT.RECORD.STATUS:1:1=Account 1000-2000-9001 IS FROZEN",
  "timestamp": "2026-10-05T13:45:00.220Z"
}
```

---

### 1.2 Verify 2FA OTP & Settle Transfer
`POST /api/v1/orchestrator/transfers/verify-otp`

#### Description
Verifies customer email/SMS OTP against Redis. Once confirmed, releases provisional hold and executes final T24 OFS funds transfer settlement.

#### Request Schema
```json
{
  "transaction_id": "TX-20261005-002",
  "otp_code": "849201"
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "transaction_id": "TX-20261005-002",
  "status": "SETTLED",
  "message": "2FA verified successfully. T24 CBS settlement committed.",
  "t24_reference": "FT26095B",
  "settled_at": "2026-10-05T13:46:12.890Z"
}
```

---

## 2. Transaction Reversal Endpoints

### 2.1 Reverse Settled Transfer
`POST /api/v1/orchestrator/transfers/{transactionId}/reverse`

#### Description
Initiates an authoritative transaction reversal for an already settled transfer. The Orchestrator validates the reversal request, translates it to Temenos OFS reversal syntax (`FUNDS.TRANSFER,REVERSE`), and dispatches it to T24 CBS. T24 verifies original transaction in Azure SQL, checks recipient available balance, executes double-entry compensating entries, and returns reversal receipt.

#### Temenos OFS Wire Syntax Mapping
* **Request String Sent to T24:**
  ```text
  FUNDS.TRANSFER,REVERSE/R/PROCESS/0/1,{tellerUserId}/{companyCode}/1,{transactionId},REVERSAL.REASON={reasonCode},SUPERVISOR.OVERRIDE={supervisorApprovalId}
  ```
* **Expected Response String from T24:**
  ```text
  {transactionId}//1/REVERSED,ORIG.TXN.ID:1:1={transactionId},REV.TXN.REF:1:1=REV-26095A,REVERSAL.DATE:1:1=20261005
  ```

#### Request Schema
* **Path Parameter:** `transactionId` *(String)* — The original settled transaction reference.
* **Request Body:**

| Field | Type | Required | Description |
| :--- | :---: | :---: | :--- |
| `reason_code` | String | Yes | Reason enum: `CUSTOMER_DISPUTE`, `OPERATIONAL_ERROR`, `DUPLICATE_TRANSFER`, `FRAUD_CLAWBACK` |
| `justification` | String | Yes | Plain-text rationale for internal audit compliance (min 10 chars) |
| `supervisor_user_id` | String | Yes | Checker / Supervisor authorizing the reversal (Segregation of Duties) |
| `reversal_type` | String | No | `FULL_REVERSAL` or `PARTIAL_REVERSAL` (defaults to `FULL_REVERSAL`) |
| `partial_amount` | Decimal| No | Amount if partial reversal (required if `PARTIAL_REVERSAL`) |

```json
{
  "reason_code": "OPERATIONAL_ERROR",
  "justification": "Incorrect beneficiary account entered by branch teller. Customer verified.",
  "supervisor_user_id": "SUP9011",
  "reversal_type": "FULL_REVERSAL"
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "reversal_id": "REV-26095A",
  "original_transaction_id": "TX-20261005-001",
  "status": "REVERSED",
  "reversal_reason": "OPERATIONAL_ERROR",
  "reversed_amount": 1500.0000,
  "fee_refunded": 15.0000,
  "source_account_id": "1000-2000-3001",
  "target_account_id": "1000-2000-3002",
  "history_table": "transaction_reversal_history",
  "t24_reversal_status": "SUCCESS",
  "raw_ofs_response": "TX-20261005-001//1/REVERSED,ORIG.TXN.ID:1:1=TX-20261005-001,REV.TXN.REF:1:1=REV-26095A",
  "timestamp": "2026-10-05T13:48:30.400Z"
}
```

#### Error Response: Recipient Insufficient Funds (HTTP 422 Unprocessable Entity)
Recorded into `transaction_reversal_history` with `reversal_status = FAILED`:
```json
{
  "reversal_id": "REV-26095A",
  "original_transaction_id": "TX-20261005-001",
  "status": "FAILED",
  "reversal_reason": "OPERATIONAL_ERROR",
  "failure_reason": "INSUFFICIENT_FUNDS_FOR_CLAWBACK",
  "error_code": "INSUFFICIENT_FUNDS_FOR_CLAWBACK",
  "history_table": "transaction_reversal_history",
  "message": "Cannot automatically reverse: recipient available balance (PHP 200.00) is less than transfer amount (PHP 1500.00). Manual dispute required.",
  "timestamp": "2026-10-05T13:48:30.500Z"
}
```

---

## 3. Amount Holds & Reservations Endpoints (`AC.LOCKED.EVENTS`)

### 3.1 Place Amount Hold / Reservation
`POST /api/v1/orchestrator/accounts/{accountId}/holds`

#### Description
Places a provisional hold on customer funds without mutating total ledger balance. Uses Temenos OFS `AC.LOCKED.EVENTS`. T24 locks the balance row in Azure SQL, increments `locked_amount`, and decrements `available_balance`.

#### Temenos OFS Wire Syntax Mapping
* **Request String Sent to T24:**
  ```text
  AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1,{userId}/{companyCode}/1,,ACCOUNT.NUMBER={accountId},FROM.DATE={fromDate},TO.DATE={expiryDate},LOCKED.AMOUNT={amount},HOLD.REASON={reason}
  ```
* **Expected Response String from T24:**
  ```text
  ACLK26095A//1/SUCCESS,ACCOUNT.NUMBER:1:1={accountId},LOCKED.AMOUNT:1:1={amount},HOLD.REF:1:1=HLD-99102
  ```

#### Request Schema
* **Path Parameter:** `accountId` *(String)* — Account on which hold will be placed.
* **Request Body:**

| Field | Type | Required | Description |
| :--- | :---: | :---: | :--- |
| `hold_amount` | Decimal | Yes | Amount to reserve (strictly positive) |
| `reason` | String | Yes | Enum: `MAKER_CHECKER_HOLD`, `CARD_PREAUTH`, `COURT_ORDER_FREEZE`, `AML_INVESTIGATION` |
| `expiry_hours` | Integer | No | Duration before auto-expiration (defaults to `24`) |
| `external_reference` | String | No | External reference (e.g. Card Authorization ID) |

```json
{
  "hold_amount": 60000.0000,
  "reason": "MAKER_CHECKER_HOLD",
  "expiry_hours": 48,
  "external_reference": "MC-TXN-88019"
}
```

#### Response Schema (HTTP 201 Created)
```json
{
  "hold_id": "HLD-99102",
  "account_id": "1000-2000-3001",
  "hold_amount": 60000.0000,
  "status": "ACTIVE",
  "t24_lock_reference": "ACLK26095A",
  "expires_at": "2026-10-07T13:50:00.000Z",
  "created_at": "2026-10-05T13:50:00.000Z"
}
```

---

### 3.2 Release / Cancel Amount Hold
`DELETE /api/v1/orchestrator/accounts/{accountId}/holds/{holdId}`

#### Description
Releases an active hold, returning reserved funds back to the customer's available balance in Azure SQL via Temenos `AC.LOCKED.EVENTS,REVERSE`.

#### Response Schema (HTTP 200 OK)
```json
{
  "hold_id": "HLD-99102",
  "account_id": "1000-2000-3001",
  "released_amount": 60000.0000,
  "status": "RELEASED",
  "message": "Hold released successfully. Funds restored to available balance.",
  "released_at": "2026-10-05T13:52:10.150Z"
}
```

---

### 3.3 Capture Hold into Final Settlement
`POST /api/v1/orchestrator/accounts/{accountId}/holds/{holdId}/capture`

#### Description
Converts the reserved hold into an authoritative settled debit against the beneficiary account. Releases the locked amount and records final ledger balance deduction in Azure SQL.

#### Request Schema
```json
{
  "target_account_id": "1000-2000-3002",
  "capture_amount": 60000.0000,
  "memo": "Maker-checker approved transfer execution"
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "hold_id": "HLD-99102",
  "transaction_id": "TX-CAPT-77891",
  "status": "SETTLED",
  "captured_amount": 60000.0000,
  "t24_reference": "FT26095C",
  "settled_at": "2026-10-05T13:53:40.000Z"
}
```

---

## 4. Failed Transaction & Retry Management Endpoints

### 4.1 Interrogate Transaction Status on CBS
`GET /api/v1/orchestrator/transfers/{transactionId}/status`

#### Description
Safely checks whether a transaction was committed to Azure SQL by T24 CBS prior to attempting an idempotent retry. Prevents double-debiting when client timeouts occur.

#### Temenos OFS Wire Syntax Mapping
* **Request:** `FUNDS.TRANSFER,STATUS/S/PROCESS/0/1,U1001/PH100223/1,{transactionId}`
* **Response:** `{transactionId}//1/COMMITTED` or `{transactionId}//-1/NOT_FOUND`

#### Response Schema (HTTP 200 OK)
```json
{
  "transaction_id": "TX-TIMEOUT-991",
  "orchestrator_state": "TIMED_OUT",
  "t24_cbs_state": "NOT_COMMITTED",
  "is_safe_to_retry": true,
  "idempotency_key": "IDEMP-991",
  "recommendation": "SAFE_TO_RETRY"
}
```

---

### 4.2 Idempotent Retry of Interrupted Transfer
`POST /api/v1/orchestrator/transfers/{transactionId}/retry`

#### Description
Executes an idempotent retry with backoff. If T24 status interrogation confirms the transfer was not previously posted, the Orchestrator safely re-dispatches the OFS payload. If max retries are exceeded, trips the Circuit Breaker and posts to Kafka DLQ (`banking.transfers.dlq`).

#### Request Schema
```json
{
  "idempotency_key": "IDEMP-991",
  "retry_attempt": 2,
  "reason": "GATEWAY_TIMEOUT_RECOVERY"
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "transaction_id": "TX-TIMEOUT-991",
  "status": "SETTLED",
  "recovery_mode": "IDEMPOTENT_RETRY_SUCCESS",
  "t24_reference": "FT26095D",
  "settled_at": "2026-10-05T13:55:12.000Z"
}
```

---

## 5. End-Of-Day (EOD) Batch Processing Endpoints

The system implements the **3 specific EOD subfeatures** chosen by the banking architecture:
1. **Interest Calculation** (`ACCOUNT.INTEREST`)
2. **Periodic Fees Assessment** (`CHARGES.COLLECTION`)
3. **General Ledger & Regulatory Reports Extraction** (`EB.REPORT.EXTRACT`)

---

### 5.1 EOD Subfeature 1: Interest Calculation & Accrual
`POST /api/v1/orchestrator/eod/interest-calculation`

#### Description
Triggers the T24 CBS batch job to compute daily interest accrual across all active savings and deposit accounts in Azure SQL. Calculates `accrued_interest = daily_balance * (annual_rate / 365)`. If the business date is the final business day of the month, posts interest credits to customer balances and records debit mutations against the Bank Interest Expense General Ledger account.

#### Temenos OFS Wire Syntax Mapping
* **Request String Sent to T24:**
  ```text
  ACCOUNT.INTEREST,PROCESS/I/PROCESS/0/1,EOD_SYSTEM/PH100223/1,,VALUE.DATE={eodDate},PROCESS.MODE=ACCRUE_AND_POST,CURRENCY=PHP
  ```
* **Expected Response String from T24:**
  ```text
  INT.BATCH.SUCCESS//1/SUCCESS,ACCOUNTS_PROCESSED:1:1=10000,TOTAL_ACCRUED:1:1=42512.80,CAPITALIZED_ACCTS:1:1=10000
  ```

#### Request Schema
```json
{
  "eod_date": "2026-10-05",
  "batch_mode": "ACCRUE_AND_POST",
  "apply_withholding_tax": true,
  "withholding_tax_rate": 0.20
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "job_id": "EOD-INT-20261005",
  "subfeature": "INTEREST_CALCULATION",
  "status": "COMPLETED",
  "accounts_evaluated": 10,
  "accounts_accrued": 10,
  "total_gross_interest_accrued": 42512.8000,
  "total_withholding_tax_deducted": 8502.5600,
  "total_net_interest_credited": 34010.2400,
  "duration_ms": 1420,
  "completed_at": "2026-10-05T14:00:01.420Z"
}
```

---

### 5.2 EOD Subfeature 2: Maintenance & Transaction Fees Assessment
`POST /api/v1/orchestrator/eod/fees`

#### Description
Triggers T24 CBS batch charges collection. Evaluates customer accounts in Azure SQL that failed to maintain the required Average Daily Balance (ADB). Deducts monthly maintenance fees (e.g. PHP 300.00) from accounts below minimum threshold and credits the Bank Fee & Commission Income GL account.

#### Temenos OFS Wire Syntax Mapping
* **Request String Sent to T24:**
  ```text
  CHARGES.COLLECTION,PROCESS/I/PROCESS/0/1,EOD_SYSTEM/PH100223/1,,VALUE.DATE={eodDate},CHARGE.CODE=BELOW_MIN_ADB_FEE,AUTO.POST=YES
  ```
* **Expected Response String from T24:**
  ```text
  CHG.BATCH.SUCCESS//1/SUCCESS,ACCOUNTS_EVALUATED:1:1=10000,CHARGES_POSTED:1:1=142,TOTAL_FEES_COLLECTED:1:1=42600.00
  ```

#### Request Schema
```json
{
  "eod_date": "2026-10-05",
  "fee_types": ["BELOW_MIN_ADB_FEE", "DORMANT_ACCOUNT_FEE"],
  "dry_run": false
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "job_id": "EOD-FEE-20261005",
  "subfeature": "FEES_ASSESSMENT",
  "status": "COMPLETED",
  "accounts_evaluated": 10,
  "accounts_charged": 2,
  "total_fees_collected": 600.0000,
  "fee_gl_account": "GL-INCOME-FEES-4001",
  "duration_ms": 890,
  "completed_at": "2026-10-05T14:00:02.310Z"
}
```

---

### 5.3 EOD Subfeature 3: General Ledger Balancing & Reports Extraction
`POST /api/v1/orchestrator/eod/reports`

#### Description
Executes comprehensive General Ledger balancing and trial balance verification ($\sum \text{Debits} == \sum \text{Credits}$). If zero delta is verified, generates immutable EOD operational and regulatory report extracts (Daily Trial Balance, Balance Sheet, BSP Compliance Report). Emits report events to Kafka for archiving in the PostgreSQL Audit Vault.

#### Temenos OFS Wire Syntax Mapping
* **Request String Sent to T24:**
  ```text
  EB.REPORT.EXTRACT,GENERATE/I/PROCESS/0/1,EOD_SYSTEM/PH100223/1,,REPORT.TYPE=GL_TRIAL_BALANCE_AND_BSP,RUN.DATE={eodDate}
  ```
* **Expected Response String from T24:**
  ```text
  REP.BATCH.SUCCESS//1/SUCCESS,TOTAL_DEBITS:1:1=1495200.00,TOTAL_CREDITS:1:1=1495200.00,BALANCE_DELTA:1:1=0.00,REPORT_ID:1:1=EOD-REP-20261005
  ```

#### Request Schema
```json
{
  "eod_date": "2026-10-05",
  "report_formats": ["JSON", "CSV", "PDF"],
  "include_bsp_regulatory_extract": true
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "job_id": "EOD-REP-20261005",
  "subfeature": "REPORTS_EXTRACTION",
  "status": "COMPLETED",
  "gl_balanced": true,
  "total_debits": 1495200.0000,
  "total_credits": 1495200.0000,
  "imbalance_delta": 0.0000,
  "generated_reports": [
    {
      "report_code": "GL_TRIAL_BALANCE",
      "name": "Daily General Ledger Trial Balance",
      "uri": "/reports/eod/20261005/gl_trial_balance.pdf"
    },
    {
      "report_code": "BSP_REGULATORY_RESERVES",
      "name": "Bangko Sentral Statutory Reserve Compliance",
      "uri": "/reports/eod/20261005/bsp_reserve_report.pdf"
    },
    {
      "report_code": "EOD_TRANSACTION_JOURNAL",
      "name": "Daily Transaction Mutation Journal",
      "uri": "/reports/eod/20261005/daily_mutations.csv"
    }
  ],
  "duration_ms": 1105,
  "completed_at": "2026-10-05T14:00:03.415Z"
}
```

---

### 5.4 Master EOD Pipeline Trigger
`POST /api/v1/orchestrator/eod/trigger`

#### Description
Automated orchestrator pipeline that runs the **3 EOD subfeatures sequentially in an atomic lifecycle**:
1. Locks the ledger perimeter against new incoming customer transfers.
2. Executes **Interest Calculation & Accrual**.
3. Executes **Fees & Charges Assessment**.
4. Executes **GL Balancing & Reports Extraction**.
5. Emits all completion events to Kafka, projects to PostgreSQL Audit Vault, and rolls business day to $T+1$.

#### Request Schema
```json
{
  "business_date": "2026-10-05",
  "target_next_date": "2026-10-06",
  "operator_user_id": "SYS_BATCH_CRON"
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "batch_run_id": "EOD-BATCH-20261005-99",
  "overall_status": "SUCCESS",
  "business_date": "2026-10-05",
  "next_business_date": "2026-10-06",
  "steps_summary": {
    "step_1_interest_calculation": "SUCCESS (₱34,010.24 net credited)",
    "step_2_fees_assessment": "SUCCESS (₱600.00 collected)",
    "step_3_gl_and_reports": "SUCCESS (GL Balanced, Delta = ₱0.00, 3 Reports Generated)"
  },
  "total_batch_duration_ms": 3415,
  "completed_at": "2026-10-05T14:00:03.500Z"
}
```

---

## 6. Communication with Other Banking Systems Endpoints

### 6.1 Initiate Outward Interbank Transfer (InstaPay / PESONet)
`POST /api/v1/orchestrator/external/transfers`

#### Description
Processes an outbound interbank remittance to a recipient in an external bank (e.g. BDO, BPI, UnionBank). The Orchestrator conducts synchronous risk evaluation, maps to T24 Outward OFS message, debits the sender account and credits the internal Nostro/Clearing Suspense Account in Azure SQL. It then generates an ISO 20022 `pacs.008` message for the external clearing switch.

#### Temenos OFS Wire Syntax Mapping
* **Request:**
  ```text
  FUNDS.TRANSFER,OUTWARD/I/PROCESS/0/1,{userId}/{companyCode}/1,{txnId},DEBIT.ACCT.NO={accountId},BENEFICIARY.ACCT={recipAcct},BENEFICIARY.BANK={bankBic},AMOUNT={amount},CLEARING.NETWORK={network}
  ```
* **Response from T24:**
  ```text
  {txnId}//1/PENDING_ACK,CLEARING.REF:1:1=IPAY-26095A,SUSPENSE.ACCT:1:1=GL-CLEARING-INSTAPAY
  ```

#### Request Schema
```json
{
  "transaction_id": "TX-EXT-20261005-001",
  "source_account_id": "1000-2000-3001",
  "target_bank_code": "BDO_PH",
  "target_bank_bic": "BNORPHMMXXX",
  "recipient_account_number": "009123456789",
  "recipient_name": "Maria Santos",
  "amount": 5000.0000,
  "currency": "PHP",
  "clearing_network": "INSTAPAY",
  "memo": "Payment for supplies"
}
```

#### Response Schema (HTTP 200 OK)
```json
{
  "transaction_id": "TX-EXT-20261005-001",
  "clearing_network": "INSTAPAY",
  "clearing_reference": "IPAY-26095A",
  "status": "SETTLED_EXTERNALLY",
  "sender_account": "1000-2000-3001",
  "recipient_bank": "BDO_PH",
  "amount": 5000.0000,
  "interbank_fee": 10.0000,
  "iso20022_message_id": "MSG-PACS008-88192",
  "iso20022_status": "ACTC (Accepted Technical Validation)",
  "settled_at": "2026-10-05T14:02:15.110Z"
}
```
