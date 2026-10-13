# Interbank Transfer Realignment & Core Banking Handover Summary

This document serves as the high-priority engineering summary and handover reference for the recent **Interbank Transfer (CooBS & AuraBank)** realignments, bilateral accounting mechanisms, DLQ governance rules, and UI updates.

---

## 1. Banking Identities & Correspondent Model

- **Our Bank:** **AuraBank**
- **Partner Bank:** **CooBS**
- **Legacy Placeholder Cleanup:** All previous references to "Bank B" or "BANKB" have been completely unified to **CooBS**.
- **Database Compatibility:** The internal database account key `ACC-NOSTRO-BANK-B` in Oracle XE / Azure SQL is retained to avoid breaking database schemas, and is canonically labeled across all user interfaces, logs, and audit entries as the **CooBS Bilateral Nostro Buffer**.

---

## 2. Double-Entry Accounting Mechanics

### Why `ACC-NOSTRO-BANK-B` and Bob Martinez Both Increase on ₱1,000 Outward Wire
Under standard correspondent banking (ISO 20022 and BSP Circular 982), AuraBank has **zero direct SQL connection** to CooBS's database.

```
+------------------------------------+          +------------------------------------+
|          AURABANK LEDGER           |          |            COOBS LEDGER            |
|                                    |          |                                    |
| 1. Alice Guo (Customer):           |          | 3. AuraBank Clearing Account:      |
|    -₱1,000 (Debit Customer)        |  HTTP    |    -₱1,000 (Debit AuraBank pool)   |
|                                    |  Wire    |                                    |
| 2. ACC-NOSTRO-BANK-B:              | -------->| 4. Bob Martinez (Customer):        |
|    +₱1,000 (Credit Bilateral Buffer)          |    +₱1,000 (Credit Customer)       |
|                                    |          |                                    |
| AuraBank Balance Sheet: ₱0.00 Net  |          | CooBS Balance Sheet: ₱0.00 Net     |
| (Double-entry money conserved)     |          | (Double-entry money conserved)     |
+------------------------------------+          +------------------------------------+
```

1. **On AuraBank's Ledger:**
   - **Alice Guo (`1000-2000-3001`):** Debited ₱1,000.
   - **`ACC-NOSTRO-BANK-B` (CooBS Nostro Pool):** Credited ₱1,000 (increases from `5,000,000` $\to$ `5,001,000`).
   - AuraBank holds Alice's debited money in its bilateral clearing buffer to back the wire payment. AuraBank's internal books balance: $\Delta(\text{Debits} - \text{Credits}) = 0$.
2. **On CooBS's Independent Ledger:**
   - CooBS receives the wire payload over HTTP.
   - CooBS debits AuraBank's bilateral clearing account and credits **Bob Martinez** (increases from `50,000` $\to$ `51,000`).

---

## 3. Dead Letter Queue (DLQ) Governance Rule

### Terminal Business Rejections vs. Technical Faults

| Failure Category | HTTP Status | Root Cause Examples | Saga Compensation (Refund) | Published to DLQ? | Rationale |
| :--- | :---: | :--- | :---: | :---: | :--- |
| **Terminal Business Rejection** | `400` / `422` | `ACCOUNT_NOT_FOUND`, `ACCOUNT_BLOCKED`, `DORMANT_ACCOUNT` | **YES** (Immediate full refund to sender) | **NO** ❌ | Replaying deterministic 4xx business validations fails 100% of the time. Logging them in DLQ creates compliance pollution. |
| **Technical Fault / Timeout** | `502` / `504` | `PARTNER_TIMEOUT`, connection drops, HTTP 500/503 | **YES** (Immediate full refund to sender) | **YES** ✅ (`banking.transfers.dlq`) | Unknown remote settlement state requires compliance operator audit, reconciliation, and possible retry. |

---

## 4. Architecture & Form Orientation in Frontend (`/cbs`)

The **Interbank (CooBS Wire)** workbench in `T24TestConsole.jsx` is strictly split into two complementary corridors:

### Left Panel: Outward Inter-Bank Wire (`AuraBank → COOBS`)
- **Flow:** AuraBank customer sends funds out to a beneficiary at CooBS.
- **Endpoint:** `POST /api/v1/transfers`
- **Leg 1:** CBS debits sender (`1000-2000-3001`), credits Nostro pool (`ACC-NOSTRO-BANK-B`).
- **Leg 2:** Transfer Orchestrator dispatches HTTP wire to CooBS partner URL.
- **Saga Safety:** If Leg 2 fails, an automated compensating reversal immediately refunds the sender.

### Right Panel: Inward Wire Ingestion Simulator (`COOBS → AuraBank`)
- **Flow:** CooBS customer dispatches an inward wire into AuraBank.
- **Endpoint:** `POST /api/v1/interbank/transfers/inward`
- **Header:** `X-Interbank-API-Key: AURABANK-SECURE-KEY-2026`
- **Payload Format:** Matches partner group expected JSON schema:
  - `sourceBankCode`: `"COOBS"`
  - `destinationBankCode`: `"AURABANK"`
  - `sender`: `{ accountNumber, name }`
  - `beneficiary`: `{ accountNumber: "1000-2000-3001", name }`
  - `amount`, `currency`, `purpose`, `senderReference`
- **Leg 1:** Ingress validates API key and checks local beneficiary exists.
- **Leg 2:** CBS debits bilateral Nostro buffer and credits local beneficiary.

---

## 5. Summary of Files Modified

| Component | File Path | Key Changes |
| :--- | :--- | :--- |
| **Orchestrator** | `backend/transfer-orchestrator/.../service/TransferOrchestrationService.java` | Gated DLQ event publishing: skips `ACCOUNT_NOT_FOUND` and `ACCOUNT_BLOCKED`; publishes only on timeouts/technical 5xx; sets default bank to `COOBS`. |
| **Orchestrator** | `backend/transfer-orchestrator/.../controller/InterbankIngressController.java` | Updated inward defaults: `sourceBankCode = "COOBS"`, `destinationBankCode = "AURABANK"`. |
| **Orchestrator** | `backend/transfer-orchestrator/.../dto/InterbankWireRequest.java` | Normalized default fallback bank code to `COOBS`. |
| **Orchestrator Test** | `backend/transfer-orchestrator/.../InterbankTransferOrchestrationTest.java` | Added unit assertions verifying zero DLQ events on business rejections (`never().publishDlqEvent(...)`). |
| **Frontend UI** | `frontend/src/components/T24TestConsole.jsx` | Fixed undefined variable `highlightBankB` $\to$ `highlightCoobs`; scrubbed all "Bank B" strings; reoriented Left Panel to `AuraBank → COOBS` and Right Panel to `COOBS → AuraBank`. |
| **Automated Suite** | `scripts/verify-all-banking-features.mjs` | Updated Feature 6 tests to assert `COOBS` and `AURABANK`; validated 24/24 pass rate. |

---

## 6. Verification Proof & System Health

1. **Full Regression Suite (`node scripts/verify-all-banking-features.mjs`):**
   - **Total Scenarios Tested:** 24
   - **Passed:** 24 (100.0%)
   - **Failed:** 0
   - Features 1 to 7 and the 800 TPS burst benchmark are all green.
2. **Container Status:** All Docker containers (`banking-frontend`, `transfer-orchestrator`, `account-service`, `gateway-service`, `t24-mock-cbs`, `compliance-service`, etc.) are healthy and running.

---

## 7. Inward Wire Ingestion & Bilateral Balance Resolution

### The Problem Diagnosed
When dispatching money back from CooBS (`Bob Martinez`) to AuraBank (`Alice Guo`):
1. **Sender Not Deducted:** Bob Martinez's balance in the CooBS Sovereign node card remained fixed at ₱50,000 / ₱51,000 because `handleExecuteInwardWire` in `T24TestConsole.jsx` recorded transaction history without deducting `partnerBankLedger.balance`.
2. **Receiver Not Displayed as Increased:** 
   - After inward wire execution, the UI was previously fetching balance for the currently selected account (`activeAccount`). If the user was viewing `ACC-NOSTRO-BANK-B`, they saw Nostro buffer being debited rather than Alice being credited.
   - Furthermore, `account-service` had a 30-second Redis read-through cache (`account:balance:{accountId}`), which served stale balance data until TTL expiration.
3. **Accounting Clarification (Why Nostro Buffer Moves on CBS):**
   - AuraBank operates under strict double-entry principles. When funds arrive from CooBS, AuraBank cannot create money out of thin air. AuraBank CBS debits `ACC-NOSTRO-BANK-B` (the bilateral liquidity buffer provided by CooBS) and credits Alice Guo (`1000-2000-3001`).
   - In a full round-trip:
     - **Outward Wire (Alice ➔ Bob, ₱1,000):** Alice (-₱1,000) ➔ Nostro (+₱1,000) ➔ Bob (+₱1,000). Nostro increases to ₱5,001,000.
     - **Inward Wire (Bob ➔ Alice, ₱1,000):** Bob (-₱1,000) ➔ Nostro (-₱1,000) ➔ Alice (+₱1,000). Nostro returns to baseline ₱5,000,000.

### Technical Implementations Delivered
1. **UI State & Real-Time Debit (`T24TestConsole.jsx`):**
   - Added `prev.balance - inAmt` deduction in `setPartnerBankLedger` upon inward wire execution.
   - Triggered green flash animation on the CooBS node (`setHighlightCoobs(true)`).
   - Automatically switches `activeAccount` to the inward beneficiary (`1000-2000-3001`) and triggers an immediate fresh balance fetch.
   - Added dynamic Live Accounting Breakdown card to the Inward Results view.
2. **Cache Eviction on Balance Inquiry (`account-service` & `transfer-orchestrator`):**
   - Added `evictBalance(accountId)` to `BalanceInquiryService.java`.
   - Enhanced `AccountController.java` to support `Cache-Control: no-cache` and `fresh=true` parameters to evict stale Redis entries on demand.
   - Added Redis key eviction (`account:balance:{beneficiary}` and `account:balance:ACC-NOSTRO-BANK-B`) in `InterbankIngressController.java` right after CBS posting.
3. **Verification Results:**
   - Tested Inward Wire: Bob Martinez ➔ Alice Guo for ₱1,000.00.
   - Result: Alice Guo balance increased by +₱1,000.00 (`25,004,340` ➔ `25,005,340`).
   - Result: CooBS Nostro Buffer debited by -₱1,000.00 (`4,995,000` ➔ `4,994,000`).
   - Result: Bob Martinez debited by -₱1,000.00 in the CooBS Sovereign Ledger.

