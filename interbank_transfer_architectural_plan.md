# Implementation Plan: Interbank Transfers via Existing Endpoints, Core Isolation & Failure Resilience

## Architectural Feasibility & Direct Answers

### 1. Is there a way to do this without adding new endpoints to the T24 Mock CBS?
**Yes, absolutely.** In enterprise banking architecture, a Core Banking System (like Temenos T24) is strictly an internal ledger custodian. It has no awareness of external bank networks, ISO 20022 clearing rails, SWIFT gateways, or partner APIs. Interbank settlement is booked as standard internal double-entry accounting against an internal **Bilateral Nostro/Vostro Buffer Account** (`ACC-NOSTRO-BANK-B`).

T24 Mock CBS requires **zero new endpoints**. All interbank transactions are processed via its existing canonical endpoints:
1. **`POST /api/v1/cbs/funds-transfer`** (Temenos OFS `FUNDS.TRANSFER`):
   - **Outward Leg 1**: Debits customer account, credits `ACC-NOSTRO-BANK-B`.
   - **Inward Leg 3**: Debits `ACC-NOSTRO-BANK-B`, credits beneficiary customer account.
2. **`POST /api/v1/cbs/reversal`** (Temenos OFS `FUNDS.TRANSFER,REVERSE`):
   - **Compensating Saga Reversal**: Reverses Leg 1 if external partner bank wire fails or times out.
3. **`GET /api/v1/cbs/accounts/{accountId}/balance`** (Temenos OFS `BALANCE.ENQUIRY`):
   - **Pre-flight Beneficiary Verification**: Validates account existence before booking inward wire.

---

### 2. Can we ensure interbank communication does NOT go into Mock T24 CBS directly?
**Yes, 100%. This is the Core Banking Zero-Trust Isolation Invariant.**
External systems (partner banks, clearing switches, mobile clients, and web browsers) must **never** send traffic directly to `t24-mock-cbs` (`:8085`):
- `t24-mock-cbs` operates exclusively on raw Temenos OFS text syntax (`text/plain`), lacks partner API key authentication, lacks JWT verification, and has no fraud/AML screening.
- All external traffic terminates at **Spring Cloud Gateway** (`:8080`) and **Transfer Orchestrator** (`:8082`).
- Only `Transfer Orchestrator` (inside the private backend network) translates business requests into internal OFS messages and calls `t24-mock-cbs`.
- External partner banks communicate **only** through the API Gateway perimeter.

---

### 3. Bilateral API Verification Keys for Inward & Outward Wires

**Yes, the Partner Bank MUST have a dedicated API verification key when calling AuraBank's Inward Endpoint.** In modern banking corridors, authentication is strictly **bilateral (mutual)**:

```mermaid
flowchart LR
    subgraph AuraBank_Domain ["AuraBank Perimeter"]
        OrchOut["InterbankWireClient<br/>(Outward Egress)"]
        OrchIn["InterbankIngressController<br/>(Inward Ingress)"]
    end

    subgraph Partner_Domain ["Partner Bank B Perimeter"]
        PartnerIn["Bank B Inbound Gateway"]
        PartnerOut["Bank B Wire Dispatcher"]
    end

    OrchOut -->|"Outward Wire: presents Bank B Key<br/>(X-Interbank-API-Key: BANK-B-ISSUED-KEY)"| PartnerIn
    PartnerOut -->|"Inward Wire: presents AuraBank Key<br/>(X-Interbank-API-Key: AURABANK-ISSUED-KEY)"| OrchIn
```

#### Dual-Key Bilateral Model:
1. **Inward Verification Key (`services.partner.inward-api-key`)**:
   - Issued by **AuraBank $\to$ Partner Bank B**.
   - When Bank B dispatches an inward wire to AuraBank (`POST /api/v1/interbank/transfers/inward`), they **must** present this key in `X-Interbank-API-Key`.
   - **Strict Enforcement Rule**: If the header is missing, blank, or does not match `inward-api-key`, the request is immediately rejected with **`HTTP 401 Unauthorized`** before parsing the body or touching the ledger.
2. **Outward Authorization Key (`services.partner.outward-api-key`)**:
   - Issued by **Partner Bank B $\to$ AuraBank**.
   - When AuraBank dispatches an outward wire to Bank B, `InterbankWireClient` attaches this key.
   - Separation of inward and outward keys ensures that rotating or revoking one key does not interrupt traffic in the opposite direction.
3. **Partner Registry Validation**:
   - Inward requests declare `originatingBank: "BANK_B"`.
   - The orchestrator validates that the provided API key matches the registered credentials for `BANK_B`. If Bank C attempts to use Bank B's credentials, it is rejected.
4. **Replay & Timestamp Protection**:
   - Inward payloads include a creation timestamp.
   - The ingress controller rejects payloads older than 300 seconds (5 minutes) to protect against replay attacks.

---

## Comprehensive Failure Modes & Resilience Engineering Matrix

Below is the complete taxonomy of how an interbank transfer can fail, with special emphasis on **Partner Bank Side Failures**, and how our architecture guarantees ledger consistency:

### Category 1: Failures on the Partner Bank Side (Outward Wire: AuraBank $\to$ Bank B)

| Failure Scenario | Root Cause at Partner Bank | HTTP / Wire Signal | Architectural Handling & Saga Compensation | Customer Impact & Recovery |
| :--- | :--- | :--- | :--- | :--- |
| **1. Invalid Beneficiary Account** | Recipient account number does not exist at Partner Bank B | Partner returns `HTTP 400 Bad Request` or `HTTP 422` (`BENEFICIARY_ACCOUNT_NOT_FOUND`) | Orchestrator catches 4xx. Executes **CBS Compensating Reversal** (`POST /api/v1/cbs/reversal`). Emits status to Kafka. | **Immediate 100% refund.** UI displays: *"Transfer rejected by Bank B: Beneficiary account invalid. Funds returned to your account."* |
| **2. Beneficiary Account Frozen / Dormant** | Recipient account is locked due to AML, sanctions, court freeze, or dormancy | Partner returns `HTTP 422 Unprocessable` (`ACCOUNT_BLOCKED` or `ACCOUNT_DORMANT`) | Orchestrator catches rejection. Executes **CBS Compensating Reversal**. Debits Nostro buffer, credits Alice. | **Immediate 100% refund.** UI displays: *"Partner bank cannot accept funds for this beneficiary account."* |
| **3. Partner Bank Gateway Timeout (> 4.0s)** | Partner bank is experiencing high load, DB deadlocks, or network latency | Client timeout guard trips at **4.0 seconds** (`TimeoutException`) | `InterbankWireClient` trips timeout guard. Immediately calls **CBS Compensating Reversal**. Publishes `TransferFailedToDlqEvent` to Kafka topic `banking.transfers.dlq`. | **Immediate 100% refund.** UI displays: *"Partner bank timed out; transaction canceled and funds refunded."* |
| **4. The "In-Doubt" Dual-Write Edge Case** | Partner bank credits beneficiary at 4.1s, but network ACK dropped right after timeout | Network disconnect / packet drop | Partner bank uses **idempotent deduplication** on `referenceId` (`txId`). Kafka DLQ records incident. End-of-Day (EOD) Nostro reconciliation resolves bilateral variance. | Zero double-crediting. Ledger auditable via Merkle audit trail. |
| **5. Partner Bank Core Crash (5xx)** | Partner Bank's database or core service crashes | Partner returns `HTTP 500 Internal Server Error` or `503 Unavailable` | Caught as server error. Executes **CBS Compensating Reversal** refunding Alice. Incident routed to DLQ. | **Immediate 100% refund.** Customer sees *"Partner bank unavailable; transfer refunded."* |
| **6. Partner Bank API Key Mismatch** | `X-Interbank-API-Key` expired, rotated, or invalid | Partner returns `HTTP 401 Unauthorized` | Caught by Orchestrator. Executes **CBS Compensating Reversal**. Raises critical Ops alert (`PARTNER_AUTH_FAILURE`). | **Immediate 100% refund.** Ops notified immediately to rotate API credentials. |
| **7. Post-Settlement Return Wire** | Partner settled wire, but recipient's AML review flags payment hours later | Partner sends asynchronous **Inward Return Wire** | Treated as Inward Wire with return reference: Debits Nostro buffer, credits Alice's account with memo *"Return of Interbank Wire"*. | Alice receives return credit; bilateral books balanced. |

---

### Category 2: Failures on the Inward Wire Side (Partner Bank B $\to$ AuraBank)

| Failure Scenario | Root Cause | Signal | Architectural Handling |
| :--- | :--- | :--- | :--- |
| **8. Missing or Invalid Partner API Key** | External caller omits or sends invalid `X-Interbank-API-Key` | Perimeter auth check fails | `InterbankIngressController` returns `HTTP 401 Unauthorized`. Zero database touches. |
| **9. Beneficiary Account Not Found in AuraBank** | Partner Bank specifies non-existent AuraBank account | Pre-flight CBS enquiry `GET /api/v1/cbs/accounts/{id}/balance` fails | Ingress controller rejects wire with `HTTP 404 NOT_FOUND`. Zero ledger mutations. Nostro buffer intact. |
| **10. Beneficiary Account Blocked in AuraBank** | Recipient account is frozen or closed | Pre-flight check detects `status != 'ACTIVE'` | Rejects wire with `HTTP 422 Unprocessable`. |
| **11. AuraBank CBS Unavailable during Inward Credit** | T24 CBS down or unreachable | Circuit breaker trips | Inward payload routed to Kafka DLQ (`banking.transfers.dlq`) for automated replay upon recovery. Returns `503` to Partner Bank for retry. |

---

### Category 3: Local Pre-Flight Failures (Before the Wire Leaves AuraBank)

| Failure Scenario | Root Cause | Signal | Architectural Handling |
| :--- | :--- | :--- | :--- |
| **12. Solvency / Insufficient Balance** | Alice has insufficient available funds | CBS Leg 1 fails with `INSUFFICIENT_FUNDS` | Transfer halted before calling partner bank. Wire is never sent. |
| **13. Fraud / AML Block** | Risk Engine score $\ge 80$ or impossible geo-velocity | Risk decision = `BLOCK` | Transfer blocked (`HTTP 403`). Zero ledger mutation. |
| **14. Cooling-Off Hold ($\ge$ ₱250,000)** | Anti-scam high value protection | Status = `Reserved` | Held in Redis for 10 minutes. Leg 1 and Leg 2 delayed until cooling-off expires. |
| **15. Biometric Verification Challenge ($\ge$ ₱50,000)** | SCA / FIDO2 requirement | Status = `Authorized` | Held until biometric challenge signature verified. |

---

## Enhanced Code Implementation Details

### Strict Inward Partner API Key Verification (`InterbankIngressController.java`)
```java
@PostMapping("/interbank/transfers/inward")
public ResponseEntity<InterbankWireResponse> handleInwardWire(
        @RequestHeader(value = "X-Interbank-API-Key", required = false) String apiKey,
        @Valid @RequestBody InterbankWireRequest request) {

    // 1. Mandatory API Key Check - Missing or Invalid results in immediate 401
    if (apiKey == null || apiKey.isBlank() || !inwardApiKey.equals(apiKey.trim())) {
        log.warn("Unauthorized inter-bank wire rejected: missing or invalid API key from originatingBank={}", 
                request.originatingBank());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new InterbankWireResponse("FAILED", null, Instant.now().toString(), 
                        "UNAUTHORIZED: Valid X-Interbank-API-Key required"));
    }

    // 2. Originating Bank Registry Verification
    if (!partnerRegistry.isRegistered(request.originatingBank())) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new InterbankWireResponse("FAILED", null, Instant.now().toString(), 
                        "FORBIDDEN: Unregistered partner bank corridor"));
    }

    // 3. Pre-flight Beneficiary Check on T24 CBS (Existing endpoint)
    Map<String, String> balance = cbsService.getAccountBalance(request.beneficiaryAccountId());
    if (balance == null || balance.isEmpty() || !balance.containsKey("accountNumber")) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new InterbankWireResponse("FAILED", null, Instant.now().toString(), 
                        "BENEFICIARY_ACCOUNT_NOT_FOUND"));
    }

    // 4. Leg 3 Core Mutation (Existing /funds-transfer endpoint)
    // Debits ACC-NOSTRO-BANK-B, Credits Beneficiary
    ...
}
```

---

## Proposed Changes

### Component: `backend/transfer-orchestrator`

#### [MODIFY] `application.properties`
Define separate inward and outward API keys:
```properties
services.partner.inward-api-key=${PARTNER_INWARD_API_KEY:AURABANK-INWARD-KEY-2026}
services.partner.outward-api-key=${PARTNER_OUTWARD_API_KEY:BANK-B-OUTWARD-KEY-2026}
services.partner.url=${PARTNER_BANK_URL:http://localhost:8082/api/v1/mock-partner/transfers}
```

#### [MODIFY] `InterbankIngressController.java`
- Enforce strict mandatory verification of `X-Interbank-API-Key` using `inward-api-key`.
- Reject null, blank, or mismatching keys with `401 Unauthorized`.

#### [MODIFY] `InterbankWireClient.java`
- Dispatches outward requests with `outward-api-key` in `X-Interbank-API-Key`.

#### [MODIFY] `TransferOrchestratorController.java`
- Existing `POST /api/v1/transfers` supports interbank transfer routing seamlessly.

---

### Component: `backend/gateway-service`

#### [MODIFY] `application.yml`
- Ensures `/api/v1/interbank/**` routes to `transfer-orchestrator` (:8082).
- Ensures `/api/v1/cbs/**` is protected from external callers.

---

### Component: `backend/t24-mock-cbs`

#### [NO CODE CHANGES]
No code changes or new endpoints added to `t24-mock-cbs`.
Existing endpoints utilized:
- `POST /api/v1/cbs/funds-transfer`
- `POST /api/v1/cbs/reversal`
- `GET /api/v1/cbs/accounts/{accountId}/balance`

---

## Verification Plan

### Automated Tests
Run the comprehensive interbank test suite:
```powershell
.\mvnw.cmd test -pl backend/transfer-orchestrator -Dtest=InterbankTransferOrchestrationTest
```
- Verify: Missing API key $\to$ `401 Unauthorized`.
- Verify: Bad API key $\to$ `401 Unauthorized`.
- Verify: Valid API key $\to$ `200 OK SETTLED`.

### Manual Verification
1. Send inward wire without `X-Interbank-API-Key` $\to$ verify `HTTP 401 Unauthorized`.
2. Send inward wire with invalid `X-Interbank-API-Key: WRONG` $\to$ verify `HTTP 401 Unauthorized`.
3. Send inward wire with valid `X-Interbank-API-Key` $\to$ verify `HTTP 200 OK SETTLED`.
