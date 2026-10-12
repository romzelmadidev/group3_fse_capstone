# Implementation Plan: Remove T24 Testing Lab, Dedicated DLQ Incidents Tab (Constrained Slow-Path Alignment) & Modernize Compliance Drive

## Goal Description
This plan establishes a production-grade banking back-office UI for Aura Console by:
1. **Removing the T24 Testing Lab Frontend (`T24TestConsole.jsx` / `/cbs`)**: Decommissioning developer/tester simulation harnesses (OFS terminal, fake scenario runners, arbitrary test transfer injector, Bank B wire simulator) so administrative banking staff interact only with real operational workflows.
2. **Promoting DLQ Incident Management to a Dedicated Tab (`/dlq`) with Full `CONSTRAINED_DLQ_REPLAY` Architectural Alignment**:
   - Deeply integrating the architecture defined in [`docs/architecture/CONSTRAINED_DLQ_REPLAY_IMPLEMENTATION_PLAN.md`](file:///c:/Users/HRR83780/Downloads/group3_fse_capstone/docs/architecture/CONSTRAINED_DLQ_REPLAY_IMPLEMENTATION_PLAN.md).
   - Rather than burying DLQ incident replay in the removed test lab, creating a first-class `DLQ incidents` page (`frontend/src/pages/Dlq.jsx` at `/dlq` for `ADMIN` role) that surfaces the **Autonomous Slow-Path Recovery Daemon** status (ShedLock-synced, 30s cron, CBS health gate), the 4-status incident lifecycle (`PENDING_REPLAY`, `MANUAL_REVIEW_REQUIRED`, `EXPIRED_CANCELLED`, `RESOLVED`), 10-minute safe TTL enforcement (BSP Circular 1160), and manual operator replay actions.
3. **Exhaustive Banking Admin Controls Matrix (Section 3.1)**: Providing an updated mapping of all banking administrator controls, confirming removals (test tools) and additions (dedicated DLQ tab), and excluding non-standard CBS system date / posting window dashboard badges.
4. **Multi-File Type Compliance Drive (Section 3.2)**: Upgrading Compliance Drive to natively recognize, classify, filter, and inspect all regulatory formats entering Azurite storage (PDF statements/tax certs, Excel GL workbooks, AMLA CTR XML packages, JSON audit payloads, CSV ledger journals, KYC verification photos/selfies, ZIP bundles, and text logs).
5. **Native Aura Console Look-and-Feel (Section 3.3)**: Completely overhauling Compliance Drive to match Aura Console design tokens (Onest/Geist fonts, ink/paper/mint/sky palette, `.panel`, `shadow-card`, native `PageHeader`, `Button`, `Badge`, `SearchBar`, and slide-over `Drawer`).

---

## 3.1 Banking Admin Features & Controls Breakdown Matrix

Below is the refined architectural matrix mapping standard core banking administrative features and controls against Aura Console:

| Banking Admin Feature / Control Domain | Purpose & Regulatory Context | Equivalent UI Control / Page in Aura Console | Action / Disposition | Detailed Rationale & Changes |
| :--- | :--- | :--- | :--- | :--- |
| **Customer 360 & Account Directory** | Search customers, inspect linked accounts, query available vs ledger vs held balances, and view transaction history. | `Customers` (`/customers`) | **Retain & Maintain** | Production-ready customer master inquiry with deep history, balance aggregation, and counterparty resolution. |
| **Protective Account Freeze & Unfreeze** | Legally required administrative lock (BSP/AMLA) for suspected phishing, lost SIM card, impossible travel anomaly, court order, or customer request. | `Freeze / Unfreeze` button on account cards in `Customers` (`/customers`) | **Retain & Maintain** | Enforces regulatory reason codes, memo recording, and audit trail attribution. |
| **Identity Verification & e-KYC (Maker-Checker)** | Review submitted government IDs, compute biometric match confidence, review Laya OCR results, and execute dual-control approval/rejection. | `Identity review` (`/kyc`) | **Retain & Maintain** | Full two-stage workflow (`PENDING_MAKER` ➔ `PENDING_CHECKER` ➔ `DONE`) with side-by-side evidence inspection. |
| **Held Transfer Monitoring & Interception** | Inspect transactions held due to high risk score (≥ 80), impossible geographic hops, or BSP Circular 1140 cooling-off timers. | `Held transfers` (`/transfers`) | **Retain & Maintain** | Allows operations to release or reject held funds with mandatory audit remarks. |
| **High-Value Outflow Dual Control (Four-Eyes)** | BSP requirement that transfers ≥ ₱500,000 require two distinct staff approvals prior to settlement. | Integrated into `Transfers` (`/transfers`) drawer (`Approve (1 of 2)` vs `Release`) | **Retain & Maintain** | Enforces segregation of duties between first approver and second releaser. |
| **Dispute Management & Compensating Reversals** | Post compensating general ledger entries to reverse mistaken or fraudulent settled transfers without mutating immutable historical logs. | `Reversals` (`/reversals`) + Reversal request action in `Customers` (`/customers`) | **Retain & Maintain** | Native Maker-Checker ticket sign-off, live transaction lifecycle viewer, and balance compensation. |
| **Suspicious Activity / Transaction Reporting (SAR / STR)** | AMLC compliance filing when transactions trigger severe AML rules or risk thresholds. | `SAR / STR` (`/sar`) | **Retain & Maintain** | Laya drafts reports automatically; maker recommends filing/dismissal; checker signs off for regulatory submission. |
| **Covered Transaction Reporting (AMLA CTR ≥ ₱500k)** | Mandatory XML filings submitted to AMLC for single/aggregate transactions meeting threshold. | Backend `ComplianceKafkaConsumer` uploads to `amla/`; viewed in `Compliance Drive` (`/drive`) | **Enhance in Drive** | XML files are generated automatically. Compliance Drive will now have dedicated `.xml` support and an `amla` category folder. |
| **Immutable Ledger Audit Trail** | Append-only ledger journal inspecting debits, credits, after-balances, mutations, and actor IDs. | `Audit trail` (`/audit`) | **Retain & Maintain** | Direct read from PostgreSQL audit vault with real-time search across transactions and accounts. |
| **Regulatory & Financial Document Archive** | Centralized write-once repository of EOD GL trial balances, reconciliation PDFs, BIR Form 2306 tax certificates, customer statements, and AMLA packages. | `Compliance Drive` (`/drive`) | **Overhaul (3.2 & 3.3)** | Retain route `/drive`, but replace foreign Google Drive template styling with Aura design system, add multi-file-type handling, and rich file drawer. |
| **Dead Letter Queue (DLQ) Incident Recovery** | Monitor asynchronous Kafka messaging failures, inspect failure diagnostics/payloads, and track autonomous vs manual replay. | Previously in `T24TestConsole.jsx` (Tab 4) | **ADD DEDICATED TAB (`/dlq`)** | **ADD `/dlq` TAB**. Dedicated first-class tab in sidebar (`DLQ incidents`), fully aligned with `CONSTRAINED_DLQ_REPLAY_IMPLEMENTATION_PLAN.md`. |
| **End-of-Day (EOD) / Close of Business (COB) Trigger** | Trigger daily batch run: interest accrual, low ADB maintenance fees, balance snapshot freezing, and generating compliance filings. | Accessible in `Compliance Drive` (`/drive`) | **Retain in Drive** | Maintain clean "Run COB Batch" button within the Compliance Drive header (where generated EOD filings are published) with real-time feedback. |
| **CBS System Date & Posting Window Status Badge** | Exposing internal CBS system date / posting window state directly on admin dashboards. | Proposed in initial draft | **DROP / EXCLUDE** | **EXCLUDED**. In line with feedback, this is an internal CBS processing artifact rather than standard banking procedure. |
| **Raw Temenos OFS Terminal (Protocol Debugger)** | Direct manual entry of raw OFS protocol strings (e.g. `FUNDS.TRANSFER,INITIATE...`). | `T24 Core Banking` (`/cbs` Tab 6) | **REMOVE** | **REMOVE**. Low-level integration testbed; inappropriate for banking staff. |
| **Mock Banking Failure Scenario Injector** | Simulated test trigger buttons for overdrafts, circular transfers, 404 accounts, and synthetic fraud. | `T24 Core Banking` (`/cbs` Tab 5) | **REMOVE** | **REMOVE**. Belongs in automated integration test suites (Postman / CI), not a banking UI. |
| **Test Transfer Simulator (Hardcoded Test Accounts)** | Seed form generating synthetic transfers between hardcoded test accounts `1000-2000-3001` and `1000-2000-3002`. | `T24 Core Banking` (`/cbs` Tab 1) | **REMOVE** | **REMOVE**. Banking staff do not initiate arbitrary mock transfers from an admin test console. |
| **Inter-Bank Sandbox Wire Simulator** | Simulator for sending and receiving test wires to/from fictional "Bank B". | `T24 Core Banking` (`/cbs` Tab 8) | **REMOVE** | **REMOVE**. Developer integration sandbox; not an administrative operations control. |

---

## Alignment with `CONSTRAINED_DLQ_REPLAY_IMPLEMENTATION_PLAN.md`

`CONSTRAINED_DLQ_REPLAY_IMPLEMENTATION_PLAN.md` defines the fast-path / slow-path resilience pattern where the **Autonomous Constrained Slow-Path Recovery Worker** (`AutonomousDlqRecoveryWorker.java` in `compliance-service`) automatically polls the PostgreSQL audit vault (`failed_transaction_audit`) every 30 seconds under Redis Distributed Locking (ShedLock), enforcing strict invariants:
1. **Liveness & Stability Gate**: CBS `/actuator/health` must be `UP`.
2. **Strict 10-Minute TTL**: Incidents older than 10 minutes are marked `EXPIRED_CANCELLED` (BSP Circular 1160 consumer protection against unexpected delayed debits).
3. **Transient Error Filter**: Transient issues (`CBS_CIRCUIT_BREAKER_OR_TIMEOUT`, `503`, `504`) are queued for replay. Semantic or poison-pill errors are marked `MANUAL_REVIEW_REQUIRED`.
4. **Pre-Flight Idempotency Check**: Transactions already committed in CBS are marked `RESOLVED` with zero double-deductions.
5. **Throttled Drip Feed**: 300ms pause between replayed dispatches.

### How the New Dedicated `DLQ Incidents` Tab (`/dlq`) Reflects This Architecture:
- **Autonomous Recovery Daemon Status Bar**:
  Displays an active operational badge at the top of the DLQ page:
  `Autonomous Recovery Daemon: Active · ShedLock Synced (30s Cycle) · 10m TTL Safety Window · Throttled Drip-Feed (300ms)`
- **Four-State Incident Lifecycle**:
  Matches `failed_transaction_audit`'s replay statuses:
  - `PENDING_REPLAY` (Ember badge): Fresh incident (<10 minutes old) waiting for the next autonomous 30s cycle, or available for manual operator acceleration.
  - `MANUAL_REVIEW_REQUIRED` (Danger badge): Poison pill, semantic failure (e.g., account closed), or exhausted attempts requiring operational review and manual resolution.
  - `EXPIRED_CANCELLED` (Neutral/Slate badge): Exceeded 10-minute safe automated replay window. Explanatory banner explains that automated deductions were halted under BSP Circular 1160 to protect the depositor.
  - `RESOLVED` (Mint badge): Successfully recovered and committed (shows `resolvedBy: AUTONOMOUS_RECOVERY_DAEMON` or operator name, `resolvedAt`, and resolution notes).
- **Incident Diagnostics & Manual Operator Controls**:
  - Drawer inspection displaying error type, error code, circuit breaker state, failure timestamp, and stack trace.
  - Formatted JSON payload viewer (`payloadJson`).
  - For `MANUAL_REVIEW_REQUIRED` and `PENDING_REPLAY`: Operator "Replay Transfer" action calling `POST /api/v1/compliance/dlq/replay/{transferId}`.
  - Direct operator resolution/dismissal notes.

---

## Proposed Changes

### 1. Shell & Routing Updates

#### [MODIFY] `frontend/src/components/Shell.jsx`
- Remove `{ to: '/cbs', label: 'T24 Core Banking', icon: Database }` from `NAV`.
- Add `{ to: '/dlq', label: 'DLQ incidents', icon: AlertOctagon }` to `NAV` (placed after `Audit trail`).
- Import `AlertOctagon` from `lucide-react` (and remove unused `Database`).

#### [MODIFY] `frontend/src/context/Auth.jsx`
- Add `'/dlq': ['ADMIN']` to `ACCESS` object in line 15:
  ```js
  const ACCESS = { '/customers': BRANCH_ROLES, '/sar': ['ADMIN'], '/geo': ['ADMIN'], '/audit': ['ADMIN'], '/dlq': ['ADMIN'] };
  ```

#### [MODIFY] `frontend/src/App.jsx`
- Remove `import T24TestConsole from './components/T24TestConsole';`.
- Add `import Dlq from './pages/Dlq';`.
- Remove `<Route path="cbs" element={<T24TestConsole />} />`.
- Add `<Route path="dlq" element={<Dlq />} />`.
- Add graceful redirect: `<Route path="cbs" element={<Navigate to="/dlq" replace />} />`.
- Redirect `<Route path="t24-test" element={<Navigate to="/dlq" replace />} />`.

#### [DELETE] `frontend/src/components/T24TestConsole.jsx`
- Delete the 2,822-line testing laboratory component.

---

### 2. New Dedicated DLQ Incidents Page (`frontend/src/pages/Dlq.jsx`)

#### [NEW] `frontend/src/pages/Dlq.jsx`
A full, native Aura Console page for administrative DLQ incident management:
- **Header**:
  - `PageHeader` with title `"DLQ incidents"` and description `"Failed transactions and message queue errors captured in the audit vault. Inspect failure diagnostics and trigger authorized replays."`
  - Action button: `Refresh` with icon `RotateCcw`.
- **Autonomous Daemon Status Widget**:
  - Shows daemon health: `Autonomous Recovery Daemon: Active (ShedLock Synced · 30s Cycle)` with indicators for `10m TTL Guard` and `Throttled Drip Feed`.
- **Summary Metrics Cards**:
  - Top row tiles:
    1. `Pending Replay` (Fresh, awaiting autonomous worker)
    2. `Manual Review Required` (Non-transient error / poison pill)
    3. `Expired / Cancelled` (Exceeded 10m TTL window)
    4. `Resolved` (Recovered via autonomous worker or manual operator)
- **Status Tabs**:
  - `Needs Attention` (`PENDING_REPLAY` + `MANUAL_REVIEW_REQUIRED`)
  - `Resolved` (`RESOLVED`)
  - `Expired / Cancelled` (`EXPIRED_CANCELLED`)
  - `All Incidents`
- **Search Bar**:
  - `SearchBar` filtering by incident ID, transaction ID, error type, or error code.
- **Incident Table**:
  - Columns: `When`, `Incident ID`, `Transfer ID`, `Error type`, `Circuit breaker`, `Status`, `Action`.
  - Badges: `ember` for `PENDING_REPLAY`, `danger` for `MANUAL_REVIEW_REQUIRED`, `mint` for `RESOLVED`, `neutral` for `EXPIRED_CANCELLED`.
- **Incident Inspection Drawer (`Drawer`)**:
  - Opens on row click.
  - Displays full incident metadata: `incidentId`, `correlationId`, `transactionId`, `failureTimestamp`.
  - Diagnostics Card: `errorType`, `errorCode`, `circuitBreakerState`, and expandable stack trace.
  - Payload Viewer: Formatted monospace view of `payloadJson` (source account, destination account, amount, currency).
  - Resolution info: `resolvedAt`, `resolvedBy`, notes.
  - Action Footer:
    - For `PENDING_REPLAY` or `MANUAL_REVIEW_REQUIRED`: Prominent `Button variant="primary"` with icon `RefreshCw` to **Replay Transfer**.
    - Calls `POST /api/v1/compliance/dlq/replay/{transferId}`.
    - Shows loading spinner, handles error notes, displays success toast (`"Transfer TXN-... replayed successfully via orchestrator!"`), and reloads incident list.
- **Empty State**:
  - Uses Aura's `Empty` component: `"Queue is clear · No failed transactions requiring replay"`.

---

### 3. Compliance Drive Overhaul (`frontend/src/components/AzuriteDrive.jsx`)

#### [MODIFY] `frontend/src/components/AzuriteDrive.jsx`
Redesign `AzuriteDrive.jsx` to adopt the Aura design system and support all file types:

1. **Aura Console Styling & Design Tokens**:
   - Replace all foreign Tailwind classes (`border-line`, `bg-surface`, `bg-sunken`, `bg-canvas`, `text-fg`, `text-fg-subtle`, `text-fg-muted`, `bg-blue-600`) with authentic Aura tokens:
     - Page background: `bg-paper` (#F7F7F7).
     - Containers: `.panel` (`bg-white rounded-2xl shadow-card`).
     - Borders: `border-ink-100`.
     - Text colors: `text-ink`, `text-ink-500`, `text-ink-400`.
     - Brand accents: `mint` (`bg-mint text-ink`, `bg-mint-wash text-mint-deep`), `sky`, `ember`, `danger`.
   - Use `PageHeader` from `../components/ui` with title `"Compliance Drive"`, informative subtitle, and action buttons:
     - `"Run COB Batch"` (`Button` variant="mint" with icon `RefreshCw` and spinning state).
     - `"Upload Document"` (`Button` variant="primary" with icon `Upload`).
     - `"Refresh"` (`Button` variant="ghost" with icon `RotateCcw`).

2. **Expanded File Types & Icon/Badge Mapping (Section 3.2)**:
   Accommodate all types in storage with dedicated metadata, icon representations, and category badges:
   - **PDF (`.pdf`)**: Customer Statements, EOD GL Reconciliations, BIR Form 2306 Certificates. (Rose/Danger-wash icon `FileText`, Badge `PDF Document`).
   - **Spreadsheets (`.xlsx`, `.xls`)**: GL Trial Balance workbooks, financial calculation sheets. (Emerald/Mint-wash icon `FileSpreadsheet`, Badge `Excel Workbook`).
   - **XML (`.xml`)**: AMLA Covered Transaction Report packages (`amla/ctr-*.xml`). (Sky/Sky-wash icon `FileCode`, Badge `AMLA XML Filing`).
   - **JSON (`.json`)**: Kafka audit snapshots, incident payloads, schema configurations. (Peri/Purple-wash icon `Braces`, Badge `JSON Data`).
   - **Delimited Data (`.csv`)**: Ledger transaction dumps, clearing batches. (Teal/Mint-wash icon `Table`, Badge `CSV Dataset`).
   - **Identity Evidence / Images (`.jpg`, `.jpeg`, `.png`, `.webp`)**: KYC front/back ID cards, customer verification selfies (`users/...`). (Ember/Ember-wash icon `Image`, Badge `KYC Image`).
   - **Archives (`.zip`, `.tar.gz`)**: Compressed audit bundles and multi-file filings. (Ink/Ink-100 icon `Archive`, Badge `ZIP Archive`).
   - **Text & Transcripts (`.txt`, `.log`)**: Batch run logs, OFS transmission transcripts. (Neutral icon `FileText`, Badge `Plaintext Log`).
   - **Office Documents (`.doc`, `.docx`)**: Regulatory compliance circulars. (Sky icon `FileText`, Badge `Word Document`).

3. **Folder Organization & Dynamic Detection**:
   - Navigation categories:
     - `ALL`: All Artifacts
     - `eod`: End-of-Day Batch Filings (`eod/gl-trial-balance-*`, `eod/bir-2306-*`, `eod/gl_eod_reconciliation_*`)
     - `statements`: Customer Statements (`statements/{accountId}/*`)
     - `amla`: AMLA CTR Filings (`amla/ctr-*.xml`)
     - `users`: KYC Identity Evidence (`users/{userId}/...`)
     - `compliance`: General Regulatory Vault (`compliance/*`)
     - Dynamic discovery for any new folder prefix returned by Azurite!

4. **Category & File Type Filter Chips**:
   - Filter chips above file list: `All Types`, `PDFs`, `Spreadsheets`, `XML Filings`, `Images`, `Data (JSON/CSV)`.

5. **Aura-Style Search & Stats Widget**:
   - Native `SearchBar` component with live count pill (`count={filteredBlobs.length} total={blobs.length}`).
   - Storage Health & Quota widget displaying formatted storage consumed, container name (`compliance-vault`), and write-once compliance status.

6. **Interactive File Inspector Drawer**:
   - When clicking any file row or grid card, open Aura's native `Drawer` component:
     - File name, category badge, last-modified timestamp.
     - SHA-256 integrity checksum card with one-click copy button.
     - Azurite storage URI with one-click copy button.
     - File size in KB/MB and MIME content type.
     - **Inline Preview**:
       - For Images: renders image thumbnail using the download URL.
       - For XML / JSON / Text: provides a snippet preview or syntax-highlighted code block.
     - Prominent "Download Artifact" button (`Button` variant="primary", icon `Download`).

7. **Direct File Upload Modal / Form**:
   - Clean upload button triggering a modal/drawer allowing staff to upload regulatory documents (`POST /api/v1/compliance/azurite/upload`) directly to `compliance/` or `amla/` with drag-and-drop support, format validation, and immediate refresh.

---

## Verification Plan

### Automated Tests
1. **Frontend Lint & Build Verification**:
   - Execute Vite build to verify clean bundling and zero broken imports or references:
     ```powershell
     cd frontend
     npm run build
     ```
   - Verify that eliminating `T24TestConsole.jsx` and adding `Dlq.jsx` compiles cleanly with exit code 0.

### Manual Verification
1. **Navigation & Shell Verification**:
   - Log into Aura Console as ADMIN.
   - Confirm sidebar navigation displays:
     - Overview
     - Customers
     - Identity review
     - Held transfers
     - Reversals
     - SAR / STR
     - Location simulator
     - Audit trail
     - **DLQ incidents** (NEW dedicated tab)
     - Compliance Drive
   - Confirm "T24 Core Banking" is completely gone.
   - Access `/cbs` in URL bar; verify clean redirect to `/dlq` or `/audit`.

2. **DLQ Incidents Tab Verification (Constrained Replay Alignment)**:
   - Click on `DLQ incidents` (`/dlq`).
   - Verify daemon status banner is displayed: `Autonomous Recovery Daemon: Active · ShedLock Synced (30s Cycle) · 10m TTL Safety Window · Throttled Drip-Feed (300ms)`.
   - Verify summary metric tiles (`Pending Replay`, `Manual Review Required`, `Expired / Cancelled`, `Resolved`).
   - Filter by tabs: `Needs Attention`, `Resolved`, `Expired / Cancelled`, `All Incidents`.
   - Click an incident row: verify `Drawer` opens showing error diagnostics, formatted JSON payload, and stack trace.
   - Test "Replay Transfer": click button, observe loading spinner and toast notification.
   - Switch to "Resolved" tab: verify resolved incident appears with `AUTONOMOUS_RECOVERY_DAEMON` or operator resolution.

3. **Compliance Drive Visual & Multi-File Type Check**:
   - Navigate to `/drive` (`Compliance Drive`).
   - Confirm page matches Aura design tokens (`.panel`, `bg-paper`, Onest font, mint accents).
   - Check file type badges & icons for `.pdf`, `.xlsx`, `.xml`, `.jpg`/`.png`, `.json`, `.csv`.
   - Filter by folder: `All`, `EOD Batch Filings`, `Customer Statements`, `AMLA Filings`, `KYC Evidence`, `Regulatory Vault`.
   - Click a file row: verify right-side Aura `Drawer` opens with SHA-256 hash, storage URI, and download button.
   - Click "Run COB Batch": verify toast notification and fresh EOD filings appear in drive.
