# T24 Mock Core Banking System (CBS) & Orchestrator: Real-World Banking Failure Scenarios Runbook

This runbook outlines the **9 real-world banking failure and edge-case scenarios** supported and validated across Aura Bank's Core Banking System (`backend/t24-mock-cbs`), Transfer Orchestrator (`backend/transfer-orchestrator`), and the [T24 Mock CBS Test Laboratory](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/frontend/src/components/T24TestConsole.jsx).

---

## 1. Summary of Banking Failure Scenarios

| # | Scenario Name | Category / Standard | Target Endpoint | Wire Protocol | Expected Outcome / Invariant |
|---|---------------|---------------------|-----------------|---------------|------------------------------|
| **1** | **Insufficient Balance & Overdraft Block** | Solvency & Pre-Settlement | `POST /api/v1/transfers` | Orchestrator JSON ➔ CBS OFS | `HTTP 400 Bad Request` with `Insufficient funds...`; Zero balance debit. |
| **2** | **Clearing Cutoff Time / Closed Window** | National Clearing (PCHC / PhilPaSS) | `POST /api/v1/cbs/funds-transfer` | Temenos OFS (`text/plain`) | `HTTP 400/500` rejection with `CBS Posting window is closed. Business status: EOD_CUTOFF`. |
| **3** | **Circular / Same-Account Transfer** | Input Hygiene & Ledger Accounting | `POST /api/v1/transfers` | Orchestrator JSON ➔ CBS OFS | `HTTP 400 Bad Request` with `Source and destination accounts must be different.` |
| **4** | **Non-Existent / Invalid Beneficiary Routing** | Directory & Routing Validation | `POST /api/v1/transfers` | Orchestrator JSON ➔ CBS OFS | `HTTP 400 Bad Request` with `Account balance not found for ID: ...` |
| **5** | **Anti-Scam 10-Minute Cooling-Off Hold (₱250k+)** | BSP Circular 1140 (Fraud Prevention) | `POST /api/v1/transfers` | Orchestrator JSON | `HTTP 200 OK` with status `Reserved`, `coolingOffRequired: true`, 600s timer; Immediate posting withheld. |
| **6** | **Biometric MFA Step-Up Challenge (₱50k+)** | Strong Customer Authentication (SCA) | `POST /api/v1/transfers` | Orchestrator JSON | `HTTP 200 OK` with status `Authorized`, `biometricRequired: true`, and challenge string returned. |
| **7** | **Four-Eyes Maker-Checker Self-Approval Violation** | BSP Circular 982 (Dual Control) | `POST /api/v1/reversals/approve` | Orchestrator JSON ➔ CBS OFS | `HTTP 400 Bad Request` with `Dual control violation: Checker ID cannot match Maker ID`. |
| **8** | **Concurrent Idempotency Deduplication** | ACID Consistency & Retry Safety | `POST /api/v1/transfers` | Orchestrator JSON ➔ CBS OFS | First call succeeds (`Posted`), second identical `idempotencyKey` returns `IDEMPOTENT_REPLAY` without duplicate debiting. |
| **9** | **Core Outage Circuit Breaker & DLQ Failover** | Resilience & Fault Tolerance | `POST /api/v1/cbs/audit/failed-transactions/simulate` | JSON / Kafka | Circuit breaker trips to `OPEN`, transaction enqueued to `banking.transfers.dlq` for operator replay. |

---

## 2. Interactive Testing via the Frontend Test Laboratory

You can execute all failure scenarios with a single click in the **T24 Mock CBS Test Laboratory** (`/admin/t24-test` or `/admin/cbs-test`):

1. **Tab 7: Banking Failure Simulator**:
   - Access the dedicated **Failure Scenarios & Resilience Workbench**.
   - Review each scenario card with its regulatory context and target route.
   - Click **"Execute Test"** on any scenario. The laboratory dispatches the live payload, checks assertion invariants, evaluates Pass/Fail criteria, and verifies that the ledger balance remained intact.
2. **Instant Cutoff Window Toggle (Header & Simulator)**:
   - Click **"Simulate Cutoff"** to transition CBS from `ONLINE` to `EOD_CUTOFF`.
   - Any transfer initiated while closed will immediately verify clearing window enforcement.
   - Click **"Re-open Window"** to restore `ONLINE` mode.
3. **Tab 1: Quick Presets Launcher**:
   - In Tab 1 (Funds Transfer), quick-select buttons pre-fill:
     - `Insufficient Balance (₱999M)`
     - `Circular Self-Transfer`
     - `Unknown Account (404)`
     - `Anti-Scam Cooling-Off (₱350k)`
     - `Biometric Step-Up (₱75k)`

---

## 3. Step-by-Step Manual Testing via cURL / CLI

### Scenario 1: Insufficient Balance / Overdraft Rejection

#### Request
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-SOLVENCY-01",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "acc-2003-sav-002",
    "amount": 999999999.00,
    "currency": "PHP",
    "description": "Overdraft test exceeding available balance",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-SOLVENCY-01"
  }'
```

#### Expected Response (HTTP 400 Bad Request)
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Insufficient funds. Account acc-2002-chk-001 has available balance 8500000.00, requested 999999999.00"
}
```
*Verification Check:* Run balance enquiry on `acc-2002-chk-001`. Balance remains unchanged at 8,500,000.00 PHP.

---

### Scenario 2: EOD Posting Window Closed (Clearing Cutoff Time)

#### Step 1: Force Close the Posting Window
```bash
curl -X POST "http://localhost:8080/api/v1/cbs/cob/posting-window?open=false"
```
*Response (`text/plain`):*
```text
//1,SUCCESS,SYSTEM.DATE.ID=SYS-DATE-1,BUSINESS.DATE=2026-10-09,STATUS=EOD_CUTOFF,POSTING.WINDOW=CLOSED
```

#### Step 2: Attempt a Funds Transfer during Cutoff
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-CUTOFF-01",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "acc-2003-sav-002",
    "amount": 1000.00,
    "currency": "PHP",
    "description": "Transfer attempt during EOD batch cutoff",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-CUTOFF-01"
  }'
```

#### Expected Response (HTTP 400 or 500 Rejection)
```json
{
  "message": "Transfer execution failed: 500 INTERNAL_SERVER_ERROR \"CBS Posting window is closed. Business status: EOD_CUTOFF\""
}
```

#### Step 3: Re-open the Window
```bash
curl -X POST "http://localhost:8080/api/v1/cbs/cob/posting-window?open=true"
```

---

### Scenario 3: Circular / Same-Account Self-Transfer

#### Request
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-CIRCULAR-01",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "acc-2002-chk-001",
    "amount": 500.00,
    "currency": "PHP",
    "description": "Circular transfer test",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-CIRCULAR-01"
  }'
```

#### Expected Response (HTTP 400 Bad Request)
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Source and destination accounts must be different."
}
```

---

### Scenario 4: Non-Existent Account / Invalid Directory Routing

#### Request
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-ROUTING-01",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "ACC-INVALID-999-NOTFOUND",
    "amount": 500.00,
    "currency": "PHP",
    "description": "Invalid routing beneficiary test",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-ROUTING-01"
  }'
```

#### Expected Response (HTTP 400 Bad Request)
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Account balance not found for ID: ACC-INVALID-999-NOTFOUND"
}
```

---

### Scenario 5: High-Value Anti-Scam 10-Minute Cooling-Off Hold (₱250,000+)

#### Regulatory Background:
Per **BSP Circular 1140 (Enhanced Risk Management for Retail Electronic Payments)**, transfers amounting to $\ge \text{PHP } 250{,}000.00$ are intercepted into a 10-minute provisional cooling-off window (`Reserved` state) to mitigate authorized push payment (APP) fraud.

#### Request
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-COOLOFF-01",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "acc-2003-sav-002",
    "amount": 300000.00,
    "currency": "PHP",
    "description": "High value payment under BSP cooling-off directive",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-COOLOFF-01"
  }'
```

#### Expected Response (HTTP 200 OK — Provisional Hold)
```json
{
  "transactionId": "TXN-COOLOFF-01",
  "status": "Reserved",
  "amount": 300000.00,
  "currency": "PHP",
  "sourceAccountId": "acc-2002-chk-001",
  "destinationAccountId": "acc-2003-sav-002",
  "message": "COOLING_OFF_PERIOD_INITIATED: High-value transaction locked for 10 minutes to protect against fraud.",
  "coolingOffRequired": true,
  "coolingOffExpiresInSeconds": 600,
  "biometricRequired": false,
  "biometricChallenge": null
}
```

---

### Scenario 6: Biometric MFA Step-Up Challenge (₱50,000+)

#### Regulatory Background:
Payments $\ge \text{PHP } 50{,}000.00$ mandate Strong Customer Authentication (SCA). If no valid cryptographic biometric signature is supplied, the orchestrator generates an authentication challenge.

#### Request
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-SCA-01",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "acc-2003-sav-002",
    "amount": 75000.00,
    "currency": "PHP",
    "description": "Payment requiring biometric step-up",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-SCA-01"
  }'
```

#### Expected Response (HTTP 200 OK — Step-Up Challenge)
```json
{
  "transactionId": "TXN-SCA-01",
  "status": "Authorized",
  "amount": 75000.00,
  "currency": "PHP",
  "sourceAccountId": "acc-2002-chk-001",
  "destinationAccountId": "acc-2003-sav-002",
  "message": "BIOMETRIC_CHALLENGE_REQUIRED: Device biometric verification required for transaction.",
  "coolingOffRequired": false,
  "coolingOffExpiresInSeconds": 0,
  "biometricRequired": true,
  "biometricChallenge": "BIO-CHALLENGE-TXN-SCA-01-..."
}
```

---

### Scenario 7: Four-Eyes Maker-Checker Self-Approval Violation

#### Regulatory Background:
Per **BSP Circular 982**, high-value adjustments and reversals strictly mandate dual authorization (`checkerId != makerId`) to eliminate insider fraud and unauthorized modifications.

#### Step 1: Create a Reversal Dispute Ticket (Maker)
```bash
curl -X POST "http://localhost:8080/api/v1/reversals/request" \
  -H "Content-Type: application/json" \
  -d '{
    "originalTransactionId": "TXN-DISP-8801",
    "reason": "CUSTOMER_DISPUTE",
    "makerId": "TELLER_ALICE"
  }'
```
*Response contains `TICKET.ID`: e.g. `9f8e7d6c-5b4a-3210-fedc-ba9876543210`.*

#### Step 2: Attempt Self-Approval using Checker == Maker
```bash
curl -X POST "http://localhost:8080/api/v1/reversals/approve" \
  -H "Content-Type: application/json" \
  -d '{
    "reversalRequestId": "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
    "checkerId": "TELLER_ALICE",
    "checkerNotes": "Attempting rogue self-approval"
  }'
```

#### Expected Response (HTTP 400 Bad Request)
```json
{
  "STATUS_CODE": "-1",
  "STATUS": "FAILURE",
  "ERROR": "DUAL_CONTROL_VIOLATION",
  "MESSAGE": "Dual control violation: Checker ID cannot match Maker ID"
}
```

---

### Scenario 8: Idempotency Deduplication & Double-Posting Prevention

#### Execution:
Send the identical transfer twice with the exact same `idempotencyKey`.

#### Call 1:
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-IDEMP-01",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "acc-2003-sav-002",
    "amount": 250.00,
    "currency": "PHP",
    "description": "Idempotent payment",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-TEST-KEY-001"
  }'
```
*Result:* HTTP 200 OK (`status: "Posted"`). Source debited by 250 PHP.

#### Call 2 (Duplicate Retry):
```bash
curl -X POST "http://localhost:8080/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "TXN-IDEMP-02",
    "sourceAccountId": "acc-2002-chk-001",
    "destinationAccountId": "acc-2003-sav-002",
    "amount": 250.00,
    "currency": "PHP",
    "description": "Idempotent payment retry",
    "deviceId": "CLI-TESTER",
    "idempotencyKey": "IDEMP-TEST-KEY-001"
  }'
```
*Result:* HTTP 200 OK with `POSTED_SUCCESSFULLY_IDEMPOTENT_REPLAY`.
*Verification Check:* Account balance was debited **only once** (250 PHP total debit, not 500 PHP).

---

### Scenario 9: Core Outage Simulation & DLQ Failover

#### Trigger Simulated Outage (HTTP 504 Gateway Timeout)
```bash
curl -X POST "http://localhost:8080/api/v1/cbs/audit/failed-transactions/simulate" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "FAIL-TIMEOUT-7701",
    "errorType": "NETWORK_TIMEOUT",
    "errorCode": "HTTP_504",
    "circuitBreakerState": "OPEN",
    "payload": "{\"sourceAccountId\":\"acc-2002-chk-001\",\"destinationAccountId\":\"acc-2003-sav-002\",\"amount\":5000.00}"
  }'
```

#### Inspect Queued Incident in DLQ
```bash
curl "http://localhost:8080/api/v1/compliance/dlq/incidents"
```
*Verify incident is persisted in the PostgreSQL Audit Vault ready for operator replay.*
