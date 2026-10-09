Here are the **T24 Mock Core Banking System (CBS)** endpoints that are **actually implemented** in the codebase for the three requested domains, along with their purpose, request/response formats, and their corresponding mappings in the `transfer-orchestrator`.

---

### Protocol Context Note: Temenos OFS
The T24 Mock CBS (`backend/t24-mock-cbs`) acts as a canonical Temenos T24 host. Its transaction and posting endpoints strictly consume and produce **Temenos Open Financial Services (OFS)** string messages (`text/plain`), using [`OfsMessageUtil`](/backend/common-contracts/src/main/java/com/bank/ledger/contracts/ofs/OfsMessageUtil.java). The `transfer-orchestrator` acts as the translation layer, converting client REST JSON payloads to/from OFS.

---

## Summary Mapping Matrix

| Category | T24 Mock CBS Endpoint | Method | Format | Mapped Orchestrator Endpoint | Orchestrator Format |
| :--- | :--- | :---: | :---: | :--- | :---: |
| **Transaction History** | `/api/v1/cbs/accounts/{accountId}/transactions` | `GET` | OFS (`text/plain`) | `/api/v1/transfers/accounts/{accountId}/transactions` | JSON Array (`AccountTransactionDto[]`) |
| **Funds Transfer** | `/api/v1/cbs/funds-transfer` | `POST` | OFS (`text/plain`) | `/api/v1/transfers` | JSON (`TransferInitiationRequest` / `Response`) |
| **Direct / Saga Reversal** | `/api/v1/cbs/reversal` | `POST` | OFS (`text/plain`) | `/api/v1/reversals/direct` (or `/compensate`) | JSON Map |
| **Dual-Control Request** | `/api/v1/cbs/reversals/request` | `POST` | OFS (`text/plain`) | `/api/v1/reversals/request` | JSON Map |
| **Dual-Control Approve** | `/api/v1/cbs/reversals/approve` | `POST` | OFS (`text/plain`) | `/api/v1/reversals/approve` | JSON Map |
| **Dual-Control Reject** | `/api/v1/cbs/reversals/reject` | `POST` | OFS (`text/plain`) | `/api/v1/reversals/reject` | JSON Map |

---

## 1. Getting a List of Account Transaction History

### T24 Mock CBS Endpoint
* **Endpoint:** `GET /api/v1/cbs/accounts/{accountId}/transactions`
* **Controller:** [`CbsBalanceController.getAccountTransactions()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsBalanceController.java#L39-L59)
* **Service:** [`CbsBalanceEnquiryService.getTransactionsByAccountId()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsBalanceEnquiryService.java)
* **Purpose:** Queries committed ledger transactions (`TransactionMaster` table) for a specific account with pagination, and serializes the result into Temenos OFS syntax (`text/plain`).
* **HTTP Method:** `GET`
* **Path Variable:** `accountId` (String)
* **Query Parameters:**
  * `page` (int, default: `0`)
  * `size` (int, default: `20`)
* **Request Payload:** None (HTTP GET).
* **CBS Response Payload (`text/plain`, OFS syntax):**
  ```text
  //1,SUCCESS,ACCOUNT.NUMBER=ACC-100001,PAGE=0,SIZE=20,COUNT=2,DATA=TXN-102:ACC-100001:ACC-100003:2500.00:PHP:INTRA_BANK:Posted:2026-10-07T14:00:00Z;;TXN-101:ACC-100001:ACC-100002:1500.00:PHP:INTRA_BANK:Posted:2026-10-07T10:00:00Z
  ```
  *Format structure:* `//1,SUCCESS,ACCOUNT.NUMBER={accountId},PAGE={page},SIZE={size},COUNT={count},DATA={txId}:{sourceAcc}:{targetAcc}:{amount}:{currency}:{txType}:{status}:{createdAt};;...`

---

### Mapped Equivalent in Orchestrator: **YES**

* **Orchestrator Endpoint:** 
  * `GET /api/v1/transfers/accounts/{accountId}/transactions` (or alias `GET /api/v1/transfers/transactions?accountId={accountId}`)
* **Controller:** [`TransferOrchestratorController.getAccountTransactions()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/TransferOrchestratorController.java#L42-L56)
* **Service:** [`CbsClientService.getAccountTransactions()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/CbsClientService.java#L59-L84)
* **How it Maps:** The orchestrator invokes the CBS OFS endpoint via `WebClient`, parses the OFS string using [`OfsMessageUtil.parseTransactionEnquiryResponse()`](/backend/common-contracts/src/main/java/com/bank/ledger/contracts/ofs/OfsMessageUtil.java#L401-L441), and transforms the records into a JSON array of [`AccountTransactionDto`](/backend/common-contracts/src/main/java/com/bank/ledger/contracts/dto/AccountTransactionDto.java).
* **Orchestrator Request Payload:** None (HTTP GET with query parameters `page` and `size`).
* **Orchestrator Response Payload (`application/json`):**
  ```json
  [
    {
      "transaction_id": "TXN-102",
      "source_account_id": "ACC-100001",
      "target_account_id": "ACC-100003",
      "amount": 2500.00,
      "currency": "PHP",
      "transaction_type": "INTRA_BANK",
      "status": "Posted",
      "memo": "Payment for services",
      "created_at": "2026-10-07T14:00:00Z"
    },
    {
      "transaction_id": "TXN-101",
      "source_account_id": "ACC-100001",
      "target_account_id": "ACC-100002",
      "amount": 1500.00,
      "currency": "PHP",
      "transaction_type": "INTRA_BANK",
      "status": "Posted",
      "memo": "Funds Transfer",
      "created_at": "2026-10-07T10:00:00Z"
    }
  ]
  ```

*(Note: T24 Mock CBS also implements an immutable compliance audit endpoint [`GET /api/v1/cbs/audit/accounts/{accountId}/mutations`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsAuditController.java#L29-L35) for querying audit entries from PostgreSQL. This is mapped to `compliance-service`, rather than `transfer-orchestrator`.)*

---

## 2. Funds Transfer Operations-Related

### T24 Mock CBS Endpoint
* **Endpoint:** `POST /api/v1/cbs/funds-transfer`
* **Controller:** [`CbsPostingController.executeFundsTransfer()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsPostingController.java#L49-L96)
* **Service:** [`CbsFundsTransferService.executeTransfer()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsFundsTransferService.java#L64-L284)
* **Purpose:** Core transaction posting engine in CBS. Executes single-step atomic funds transfer:
  1. Checks posting window status against `SystemDateMaster`.
  2. Applies ascending alphabetical row-locking (`findByAccountIdForUpdate`) on source and target balances to avoid database deadlocks.
  3. Verifies account solvency (available balance).
  4. Updates balances atomically.
  5. Inserts balanced double-entry General Ledger journal lines (`GlLedgerMaster` for GL `20100`) and updates GL balances.
  6. Records `TransactionMaster` and `TransactionStatusHistoryMaster`.
  7. Publishes `TransferExecutedEvent` and `TransactionStatusChangedEvent` over Kafka and writes to the transactional outbox table.
  8. Enforces idempotency via `idempotencyKey` / `TXN.ID`.
* **Headers:** `Content-Type: text/plain`, `Accept: text/plain`
* **CBS Request Payload (`text/plain`, OFS syntax):**
  ```text
  FUNDS.TRANSFER,INITIATE/I/PROCESS//TXN-9001,USER01/TXN-9001,TRANSACTION.TYPE=AC,DEBIT.ACCT.NO=ACC-100001,CREDIT.ACCT.NO=ACC-100002,AMOUNT=5000.00,CURRENCY=PHP,VALUE.DATE=20261009,DESCRIPTION=Payment,IDEMPOTENCY.KEY=IDEMP-9001
  ```
* **CBS Response Payload (`text/plain`, OFS syntax):**
  * **Success (HTTP 200):**
    ```text
    //1,SUCCESS,TXN.ID=TXN-9001,MESSAGE=POSTED_SUCCESSFULLY
    ```
  * **Idempotent Duplicate Replay (HTTP 200):**
    ```text
    //1,SUCCESS,TXN.ID=TXN-9001,MESSAGE=POSTED_SUCCESSFULLY_IDEMPOTENT_REPLAY
    ```
  * **Failure / Insufficient Funds (HTTP 400):**
    ```text
    //-1,FAILURE,ERROR=ERROR,MESSAGE=Insufficient funds. Account ACC-100001 has available balance 500.00, requested 5000.00
    ```

---

### Mapped Equivalent in Orchestrator: **YES**

* **Orchestrator Endpoint:** `POST /api/v1/transfers`
* **Controller:** [`TransferOrchestratorController.initiateTransfer()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/TransferOrchestratorController.java#L74-L79)
* **Service:** [`TransferOrchestrationService.initiateTransfer()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/TransferOrchestrationService.java#L48-L153) and [`CbsClientService.postToCbs()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/service/CbsClientService.java#L86-L129)
* **How it Maps:**
  1. Client sends JSON [`TransferInitiationRequest`](/backend/transfer-orchestrator/src/dto/TransferInitiationRequest.java).
  2. Orchestrator locks idempotency key, runs fraud risk checks against `risk-service`, verifies biometric challenge if $\ge \text{PHP } 50{,}000$, or enters a 10-minute cooling-off window if $\ge \text{PHP } 250{,}000$.
  3. Formulates the OFS message string via [`OfsMessageUtil.buildFundsTransferInitiate`](/backend/common-contracts/src/main/java/com/bank/ledger/contracts/ofs/OfsMessageUtil.java#L22-L30) and calls `POST /api/v1/cbs/funds-transfer` (wrapped with Resilience4j `@CircuitBreaker` and `@Retry`, routing to DLQ topic on outage).
* **Orchestrator Request Payload (`application/json`):**
  ```json
  {
    "transactionId": "TXN-9001",
    "sourceAccountId": "ACC-100001",
    "destinationAccountId": "ACC-100002",
    "amount": 5000.00,
    "currency": "PHP",
    "description": "Supplier payment",
    "deviceId": "DEVICE-MOB-01",
    "idempotencyKey": "IDEMP-9001",
    "biometricSignature": null,
    "scamAdvisoryAcknowledged": false
  }
  ```
* **Orchestrator Response Payload (`application/json`):**
  ```json
  {
    "transactionId": "TXN-9001",
    "status": "Posted",
    "amount": 5000.00,
    "currency": "PHP",
    "sourceAccountId": "ACC-100001",
    "destinationAccountId": "ACC-100002",
    "message": "Transfer executed successfully on CBS core",
    "coolingOffRequired": false,
    "coolingOffExpiresInSeconds": 0,
    "biometricRequired": false,
    "biometricChallenge": null,
    "processedAt": "2026-10-09T02:20:00.123Z"
  }
  ```

---

## 3. Transaction Reversal-Related

T24 Mock CBS implements two distinct reversal paths:
1. **Automated / Direct Saga Compensating Reversal** (used by distributed transactions/sagas).
2. **Dual-Control (Maker-Checker / Four-Eyes Principle) Reversal Workflow** (used by tellers/back-office operations).

---

### 3A. Automated / Saga Compensating Reversal

#### T24 Mock CBS Endpoint
* **Endpoint:** `POST /api/v1/cbs/reversal`
* **Controller:** [`CbsPostingController.executeReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsPostingController.java#L103-L139)
* **Service:** [`CbsReversalService.requestReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsReversalService.java#L62-L118) & [`CbsReversalService.approveReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsReversalService.java#L121-L280)
* **Purpose:** Immediate compensating reversal for saga recovery. Auto-generates a ticket and approves it in a single synchronous call: checks beneficiary solvency, refunds the original sender, posts compensating double-entry GL ledger lines, updates status to `Reversed`, records a `REVERSAL` transaction entry, and emits `TransferReversedEvent`.
* **Headers:** `Content-Type: text/plain`, `Accept: text/plain`
* **CBS Request Payload (`text/plain`, OFS syntax):**
  ```text
  FUNDS.TRANSFER,REVERSAL/I/PROCESS//REV-001,SAGA_COORDINATOR/123456,ORIGINAL.FT.NO=TXN-9001,REASON=SAGA_COMPENSATION,CHECKER.ID=SYSTEM_SAGA,MAKER.ID=SAGA_COORDINATOR
  ```
* **CBS Response Payload (`text/plain`, OFS syntax):**
  * **Success (HTTP 200):**
    ```text
    //1,SUCCESS,TXN.ID=b2f67923-a5c9-4a7f-b883-93d395786a34,MESSAGE=REVERSAL_APPROVED_AND_SETTLED
    ```
  * **Failure (HTTP 400):**
    ```text
    //-1,FAILURE,ERROR=MISSING_ORIGINAL_FT_NO,MESSAGE=ORIGINAL.FT.NO is required in OFS payload
    ```

#### Mapped Equivalent in Orchestrator: **YES**
* **Orchestrator Endpoints:** `POST /api/v1/reversals/direct` and `POST /api/v1/reversals/compensate`
* **Controller:** [`ReversalOrchestratorController.directReversal()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/ReversalOrchestratorController.java#L80-L98)
* **How it Maps:** Converts JSON map to OFS `FUNDS.TRANSFER,REVERSAL/...`, invokes `POST /api/v1/cbs/reversal`, and parses the returned OFS fields into a JSON response.
* **Orchestrator Request Payload (`application/json`):**
  ```json
  {
    "originalTransactionId": "TXN-9001",
    "reason": "SAGA_COMPENSATION",
    "makerId": "SAGA_COORDINATOR",
    "checkerId": "SYSTEM_SAGA"
  }
  ```
* **Orchestrator Response Payload (`application/json`):**
  ```json
  {
    "STATUS_CODE": "1",
    "STATUS": "SUCCESS",
    "TXN.ID": "b2f67923-a5c9-4a7f-b883-93d395786a34",
    "MESSAGE": "REVERSAL_APPROVED_AND_SETTLED"
  }
  ```

---

### 3B. Dual-Control (Maker-Checker) Reversal Workflow

Three separate endpoints are implemented in [`CbsReversalController`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsReversalController.java):

#### 1. Request Reversal (Maker initiates dispute)
* **T24 Mock Endpoint:** `POST /api/v1/cbs/reversals/request`
* **Controller:** [`CbsReversalController.requestReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsReversalController.java#L31-L57)
* **Service:** [`CbsReversalService.requestReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsReversalService.java#L62-L118)
* **Purpose:** Teller/Maker submits a reversal ticket for a `POSTED` transaction. Updates the transaction status to `PendingReversal`, persists a `PENDING` ticket in `ReversalRequestMaster`, and emits `TransactionStatusChangedEvent`.
* **CBS Request Payload (`text/plain`, OFS syntax):**
  ```text
  FUNDS.TRANSFER,REVERSAL.REQUEST/I/PROCESS//TXN-9001,MAKER01/123456,ORIGINAL.FT.NO=TXN-9001,REASON=CUSTOMER_DISPUTE,MAKER=MAKER01
  ```
* **CBS Response Payload (`text/plain`, OFS syntax):**
  ```text
  //1,SUCCESS,TICKET.ID=550e8400-e29b-41d4-a716-446655440000,STATUS=PENDING,ORIGINAL.FT.NO=TXN-9001,REVERSAL.TX.ID=,MESSAGE=Reversal request registered
  ```
* **Mapped Equivalent in Orchestrator:** **YES**
  * **Orchestrator Endpoint:** `POST /api/v1/reversals/request`
  * **Controller:** [`ReversalOrchestratorController.requestReversal()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/ReversalOrchestratorController.java#L26-L42)
  * **Orchestrator Request Payload (`application/json`):**
    ```json
    {
      "originalTransactionId": "TXN-9001",
      "reason": "Customer fraud claim",
      "makerId": "TELLER_01"
    }
    ```
  * **Orchestrator Response Payload (`application/json`):**
    ```json
    {
      "STATUS_CODE": "1",
      "STATUS": "PENDING",
      "TICKET.ID": "550e8400-e29b-41d4-a716-446655440000",
      "ORIGINAL.FT.NO": "TXN-9001",
      "MESSAGE": "Reversal request registered"
    }
    ```

---

#### 2. Approve Reversal (Checker approves & settles)
* **T24 Mock Endpoint:** `POST /api/v1/cbs/reversals/approve`
* **Controller:** [`CbsReversalController.approveReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsReversalController.java#L59-L83)
* **Service:** [`CbsReversalService.approveReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsReversalService.java#L121-L280)
* **Purpose:** Supervisor/Checker approves the reversal ticket. Strictly enforces dual-control (`checkerId != makerId`), debits the original recipient, refunds the original sender, posts balanced GL compensating records, flags original transaction as `Reversed`, and records the reversal transaction.
* **CBS Request Payload (`text/plain`, OFS syntax):**
  ```text
  FUNDS.TRANSFER,REVERSAL/I/PROCESS//550e8400-e29b-41d4-a716-446655440000,MGR02/123456,TICKET.ID=550e8400-e29b-41d4-a716-446655440000,CHECKER=MGR02,REASON=Approved
  ```
* **CBS Response Payload (`text/plain`, OFS syntax):**
  ```text
  //1,SUCCESS,TICKET.ID=550e8400-e29b-41d4-a716-446655440000,STATUS=APPROVED,ORIGINAL.FT.NO=TXN-9001,REVERSAL.TX.ID=7c9e6679-7425-40de-944b-e07fc1f90ae7,MESSAGE=Reversal executed successfully
  ```
* **Mapped Equivalent in Orchestrator:** **YES**
  * **Orchestrator Endpoint:** `POST /api/v1/reversals/approve`
  * **Controller:** [`ReversalOrchestratorController.approveReversal()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/ReversalOrchestratorController.java#L44-L60)
  * **Orchestrator Request Payload (`application/json`):**
    ```json
    {
      "reversalRequestId": "550e8400-e29b-41d4-a716-446655440000",
      "checkerId": "MGR_02",
      "checkerNotes": "Approved after dispute investigation"
    }
    ```
  * **Orchestrator Response Payload (`application/json`):**
    ```json
    {
      "STATUS_CODE": "1",
      "STATUS": "APPROVED",
      "TICKET.ID": "550e8400-e29b-41d4-a716-446655440000",
      "ORIGINAL.FT.NO": "TXN-9001",
      "REVERSAL.TX.ID": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "MESSAGE": "Reversal executed successfully"
    }
    ```

---

#### 3. Reject Reversal (Checker rejects)
* **T24 Mock Endpoint:** `POST /api/v1/cbs/reversals/reject`
* **Controller:** [`CbsReversalController.rejectReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/controller/CbsReversalController.java#L85-L110)
* **Service:** [`CbsReversalService.rejectReversal()`](/backend/t24-mock-cbs/src/main/java/com/bank/cbs/service/CbsReversalService.java#L282-L337)
* **Purpose:** Supervisor rejects the reversal request. Reverts the original transaction status from `PendingReversal` back to `Posted`, updates ticket status to `REJECTED`, and emits `TransactionStatusChangedEvent`.
* **CBS Request Payload (`text/plain`, OFS syntax):**
  ```text
  FUNDS.TRANSFER,REVERSAL.REJECT/I/PROCESS//550e8400-e29b-41d4-a716-446655440000,MGR02/123456,TICKET.ID=550e8400-e29b-41d4-a716-446655440000,CHECKER=MGR02,REASON=Insufficient evidence of fraud
  ```
* **CBS Response Payload (`text/plain`, OFS syntax):**
  ```text
  //1,SUCCESS,TICKET.ID=550e8400-e29b-41d4-a716-446655440000,STATUS=REJECTED,ORIGINAL.FT.NO=TXN-9001,REVERSAL.TX.ID=,MESSAGE=Reversal rejected
  ```
* **Mapped Equivalent in Orchestrator:** **YES**
  * **Orchestrator Endpoint:** `POST /api/v1/reversals/reject`
  * **Controller:** [`ReversalOrchestratorController.rejectReversal()`](/backend/transfer-orchestrator/src/main/java/com/bank/orchestrator/controller/ReversalOrchestratorController.java#L62-L78)
  * **Orchestrator Request Payload (`application/json`):**
    ```json
    {
      "reversalRequestId": "550e8400-e29b-41d4-a716-446655440000",
      "checkerId": "MGR_02",
      "rejectionReason": "Insufficient evidence of fraud"
    }
    ```
  * **Orchestrator Response Payload (`application/json`):**
    ```json
    {
      "STATUS_CODE": "1",
      "STATUS": "REJECTED",
      "TICKET.ID": "550e8400-e29b-41d4-a716-446655440000",
      "ORIGINAL.FT.NO": "TXN-9001",
      "MESSAGE": "Reversal rejected"
    }
    ```