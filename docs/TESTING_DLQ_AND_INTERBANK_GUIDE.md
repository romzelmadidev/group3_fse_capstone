# Complete Testing Guide: Dead Letter Queues (DLQ) & Interbank Transfers

This comprehensive testing runbook is based directly on the actual codebase implementation across `backend/transfer-orchestrator`, `backend/t24-mock-cbs`, `backend/compliance-service`, and `frontend/src/pages/Dlq.jsx`.

---

## 1. Architectural Summary & Ground Truth in Code

### A. Dead Letter Queues (DLQ) & Replaying
* **Kafka DLQ Topic**: `banking.transfers.dlq`
  * Emitted by `CbsClientService.java` whenever core banking timeout exceptions, circuit breaker opens (`CBS_CIRCUIT_BREAKER_OR_TIMEOUT`), or partner timeout exceptions occur (`PARTNER_TIMEOUT`).
  * Terminal business validation rejections (such as `ACCOUNT_NOT_FOUND` or `ACCOUNT_BLOCKED`) **do NOT pollute the DLQ**; they trigger immediate customer refunds via Saga reversal without DLQ emission.
* **Audit Vault Persistence**: 
  * `CbsAuditSelfConsumptionService.java` listens to `banking.transfers.dlq` (`groupId = "cbs-audit-workers"`).
  * Stores each failed event in PostgreSQL table `failed_transaction_audit` with `replayStatus = "PENDING_REPLAY"`, capturing payload, error type, error code, circuit breaker state, and stack trace.
* **Dual Replay Mechanisms**:
  1. **Autonomous Constrained Recovery Daemon (`AutonomousDlqRecoveryWorker.java`)**:
     * Runs periodically (default: 30s) using ShedLock distributed locking (`@SchedulerLock`).
     * **Guard 1 (Health Pre-check)**: Probes CBS `/actuator/health`. Skips cycle if CBS is down.
     * **Guard 2 (Transient Error Filter)**: Replays only transient errors (`TIMEOUT`, `503`, `504`, `CIRCUIT_BREAKER`, `CONNECTION`). Marks non-transient errors as `MANUAL_REVIEW_REQUIRED`.
     * **Guard 3 (Aging TTL Guard - BSP Circular 1160)**: If failure timestamp exceeds 10 minutes (`maxAgeMinutes`), incident is marked `EXPIRED_CANCELLED` to avoid stale replays.
     * **Guard 4 (Pre-Flight Idempotency Check)**: Checks CBS transaction status history. If already posted before timeout, marks `RESOLVED` with zero mutations.
     * **Guard 5 (Throttled Drip-Feed)**: Injects 300ms delay between replays to prevent cascading thundering herd outages.
  2. **Manual Operator Replay**:
     * Endpoint: `POST /api/v1/compliance/dlq/replay/{transferId}` (facade in `ComplianceController.java`).
     * Reconstructs payload, posts to `/api/v1/transfers`, and updates audit record via CBS `/api/v1/cbs/audit/failed-transactions/{id}/resolve`.
     * Dedicated Frontend UI: `frontend/src/pages/Dlq.jsx`.

---

### B. Interbank Transfers (Bilateral Nostro/Vostro Model)
* **Participant Directory Facade**:
  * `InterbankDirectoryController.java` (`GET /api/v1/interbank/banks`):
  * Queries CBS OFS directory and returns JSON metadata for active banks (e.g. `COOBS` BIC `COOBPHM2XXX`, clearing `BILATERAL`, nostro `ACC-NOSTRO-BANK-B`).
* **Outward Wire Transfer (AuraBank -> Partner Bank)**:
  * Triggered via standard transfer endpoint: `POST /api/v1/transfers` with `destinationBank: "COOBS"`.
  * **Leg 1 (CBS Local Mutation)**: Debits sender account and credits Nostro buffer account `ACC-NOSTRO-BANK-B`.
  * **Leg 2 (Partner HTTP Wire Dispatch)**: Dispatches wire via `InterbankWireClient.java` with a strict 4-second timeout.
  * **Saga Compensation**: If Leg 2 times out or is rejected:
    * Automatically triggers CBS Compensating Reversal (`cbsService.executeCompensatingReversal(txId, ...)`).
    * If timeout/network drop, publishes event to Kafka DLQ (`banking.transfers.dlq`).
    * Returns sanitized error to customer (zero technical Saga jargon leaked).
  * **Simulation Modes supported in test payloads**:
    * `simulationMode: "TIMEOUT"` -> Forces 5.5s lag to verify Saga rollback.
    * `simulationMode: "REJECT"` -> Simulates recipient account not found (400).
    * `simulationMode: "BLOCKED"` -> Simulates dormant/blocked account (422).
* **Inward Wire Ingestion (Partner Bank -> AuraBank)**:
  * Handled by `InterbankIngressController.java` (`POST /api/v1/interbank/transfers/inward`).
  * **Security Boundary**: Validates headers `X-Interbank-API-Key` or `X-API-Key` matching `AURABANK-SECURE-KEY-2026` (returns 401 if missing or invalid).
  * **Beneficiary Validation**: Checks beneficiary exists in CBS (returns 400 `ACCOUNT_NOT_FOUND` if missing).
  * **Leg 3 Mutation**: Debits Nostro buffer `ACC-NOSTRO-BANK-B` and credits local beneficiary.
  * **Masked PII**: Responds with masked beneficiary name (e.g., `Juan Dela Cruz` -> `J*** D*** C***`).

---

## 2. Test Execution Quick Start

### Option A: Run Unit Test Suites (No Running Containers Required)
Execute Maven directly in the backend directories:

```powershell
# 1. Run Transfer Orchestrator Interbank Unit & Saga Tests
cd backend\transfer-orchestrator
mvn test "-Dtest=Interbank*Test"

# 2. Run Autonomous DLQ Recovery Worker Unit Tests
cd ..\compliance-service
mvn test "-Dtest=AutonomousDlqRecoveryWorkerTest"

# 3. Run CBS Immutable Audit & Merkle Vault Tests
cd ..\t24-mock-cbs
mvn test "-Dtest=CbsAudit*Test"
```

---

### Option B: Run Automated End-to-End Test Scripts (Live Stack)

Make sure the Docker containers are running:
```powershell
cd infrastructure
docker compose up -d
```

#### Running the Node.js Test Suite:
```powershell
node scripts/test-dlq-and-interbank.mjs
```

#### Running the PowerShell Test Suite:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/test-dlq-and-interbank.ps1
```

---

## 3. Step-by-Step Manual cURL & Postman Verification

### Feature 1: Dead Letter Queues & Replaying

#### 1. Inspect Autonomous Recovery Daemon Status
```bash
curl -s -X GET http://localhost:8080/api/v1/compliance/dlq/status
```
*Expected Response (`200 OK`)*:
```json
{
  "autonomousDaemonActive": true,
  "shedLockSynced": true,
  "cbsHealthy": true,
  "maxAgeMinutes": 10,
  "batchSize": 10,
  "throttleMs": 300,
  "metrics": {
    "replaysAttempted": 0,
    "replaysSucceeded": 0,
    "replaysExpired": 0
  }
}
```

#### 2. Query DLQ Incidents in PostgreSQL Audit Vault
```bash
curl -s -X GET "http://localhost:8080/api/v1/cbs/audit/failed-transactions?status=ALL&page=0&size=10"
```

#### 3. Trigger Manual Replay of a Failed Incident
```bash
curl -s -X POST http://localhost:8080/api/v1/compliance/dlq/replay/TXN-DLQ-1001 \
  -H "Content-Type: application/json" \
  -d '{
    "notes": "Manual operator replay from compliance runbook",
    "operatorId": "COMPLIANCE_OPERATOR"
  }'
```

---

### Feature 2: Interbank Transfers

#### 1. Query Participant Banks Directory
```bash
curl -s -X GET http://localhost:8080/api/v1/interbank/banks
```
*Expected Response (`200 OK`)*:
```json
[
  {
    "bankCode": "COOBS",
    "bankName": "Cooperative Overseas Banking System (CooBS)",
    "bicSwiftCode": "COOBPHM2XXX",
    "clearingNetwork": "BILATERAL",
    "nostroAccountId": "ACC-NOSTRO-BANK-B",
    "isActive": true
  },
  {
    "bankCode": "AURABANK",
    "bankName": "AuraBank Core Retail",
    "bicSwiftCode": "AURAPHM1XXX",
    "clearingNetwork": "CORE",
    "nostroAccountId": "ACC-NOSTRO-BANK-B",
    "isActive": true
  }
]
```

#### 2. Outward Wire Transfer (Happy Path)
```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "IB-OUT-TEST-001",
    "sourceAccountId": "1000-2000-3001",
    "destinationAccountId": "B-ACC-998877",
    "amount": 1500.00,
    "currency": "PHP",
    "destinationBank": "COOBS",
    "senderName": "Alice Reyes",
    "description": "Outward interbank payment",
    "idempotencyKey": "IDEMP-IB-OUT-001"
  }'
```
*Expected Result*: Status `200 OK`, `status: "Posted"`, message contains partner reference.

#### 3. Outward Wire Timeout -> Saga Compensating Reversal & DLQ Publication
```bash
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "IB-OUT-TIM-001",
    "sourceAccountId": "1000-2000-3001",
    "destinationAccountId": "B-ACC-998877",
    "amount": 500.00,
    "currency": "PHP",
    "destinationBank": "COOBS",
    "senderName": "Alice Reyes",
    "simulationMode": "TIMEOUT",
    "idempotencyKey": "IDEMP-IB-TIM-001"
  }'
```
*Expected Result*: 
* Status `502 BAD_GATEWAY`
* Response body: `"We could not confirm the transfer with the recipient bank. Any deducted funds have been returned to your account."`
* CBS debited funds refunded back to sender via Saga compensating reversal.
* Event published to Kafka DLQ `banking.transfers.dlq`.

#### 4. Inward Wire Ingestion (Leg 3 Nostro Debit -> Beneficiary Credit)
```bash
curl -s -X POST http://localhost:8080/api/v1/interbank/transfers/inward \
  -H "Content-Type: application/json" \
  -H "X-Interbank-API-Key: AURABANK-SECURE-KEY-2026" \
  -d '{
    "messageId": "WIRE-IN-TEST-001",
    "sourceBankCode": "COOBS",
    "destinationBankCode": "AURABANK",
    "sender": {
      "accountNumber": "B-ACC-112233",
      "name": "Carlos Yulo"
    },
    "beneficiary": {
      "accountNumber": "1000-2000-3001",
      "name": "Alice Reyes"
    },
    "amount": 3200.00,
    "currency": "PHP",
    "purpose": "Interbank settlement credit"
  }'
```
*Expected Result*: Status `200 OK`, `status: "ACCEPTED"`, receiverReference generated, beneficiary masked as `A*** R***`.

#### 5. Inward Wire Security Gate (Unauthorized Key Rejection)
```bash
curl -s -X POST http://localhost:8080/api/v1/interbank/transfers/inward \
  -H "Content-Type: application/json" \
  -H "X-Interbank-API-Key: FORGED-KEY-999" \
  -d '{
    "messageId": "WIRE-IN-ROGUE-001",
    "sourceBankCode": "COOBS",
    "destinationBankCode": "AURABANK",
    "beneficiary": {
      "accountNumber": "1000-2000-3001",
      "name": "Alice Reyes"
    },
    "amount": 1000.00
  }'
```
*Expected Result*: Status `401 UNAUTHORIZED`, reasonCode `"UNAUTHORIZED"`. Zero CBS touch.

---

## 4. Summary Table of Test Cases & Verifications

| Test ID | Feature | Scenario | Positive / Negative | Expected Outcome | Verification Point |
|---|---|---|---|---|---|
| **DLQ-01** | DLQ | Daemon Status & ShedLock | Positive | `200 OK` | `autonomousDaemonActive: true`, `shedLockSynced: true`, 10m TTL guard |
| **DLQ-02** | DLQ | Incident Backlog Query | Positive | `200 OK` | Queries PostgreSQL `failed_transaction_audit` table |
| **DLQ-03** | DLQ | Manual Operator Replay | Positive | `200 OK` | Replays transaction payload via orchestrator, resolves incident |
| **DLQ-04** | DLQ | Direct Audit Resolution | Positive | `200 OK` | Updates audit vault with operator ID & notes |
| **IB-01** | Interbank | Participant Directory Facade | Positive | `200 OK` | Retrieves `COOBS` & `AURABANK` clearing metadata via OFS |
| **IB-02** | Interbank | Outward Wire (Happy Path) | Positive | `200 OK` | Leg 1: Debits Sender & Credits Nostro; Leg 2: Dispatches wire |
| **IB-03** | Interbank | Outward Wire Network Timeout | Negative | `502 BAD_GATEWAY` | Saga reversal refunds customer; Kafka DLQ event emitted |
| **IB-04** | Interbank | Outward Wire Recipient 404 | Negative | `400 BAD_REQUEST` | Customer refunded; Zero DLQ pollution (business validation) |
| **IB-05** | Interbank | Inward Wire Settlement | Positive | `200 OK` | Leg 3: Nostro debited & Beneficiary credited; Name masked |
| **IB-06** | Interbank | Inward Wire Missing/Forged Key| Negative | `401 UNAUTHORIZED` | Immediate rejection; zero CBS ledger mutation |
| **IB-07** | Interbank | Inward Wire Non-Existent Payee | Negative | `400 BAD_REQUEST` | Rejection with `ACCOUNT_NOT_FOUND`; zero ledger mutation |
