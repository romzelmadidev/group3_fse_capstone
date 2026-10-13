# Core Banking Platform: Comprehensive Technical Breakdown & Systems Architecture

This document provides a rigorous, deep technical specification of the core banking platform. It details component architectures, microservice contracts, database schemas, cryptographic and concurrency mechanisms, request-response lifecycles, and sequence diagrams covering every happy and negative execution path across all implemented banking capabilities.

---

## Table of Contents

1. [Architectural Topology & Microservice Infrastructure](#1-architectural-topology--microservice-infrastructure)
   - [1.1 Physical & Logical System Topology](#11-physical--logical-system-topology)
   - [1.2 Microservice Component Matrix & Port Allocations](#12-microservice-component-matrix--port-allocations)
   - [1.3 Multi-Tier Data Storage Architecture](#13-multi-tier-data-storage-architecture)
2. [Advanced Technical Concepts & Protocols Explained](#2-advanced-technical-concepts--protocols-explained)
   - [2.1 Temenos Open Financial Services (OFS) Wire Protocol](#21-temenos-open-financial-services-ofs-wire-protocol)
   - [2.2 Concurrency & Concurrency Isolation: `UPDLOCK, ROWLOCK`](#22-concurrency--concurrency-isolation-updlock-rowlock)
   - [2.3 Distributed Idempotency Engine (Redis `SETNX` & Hash Storage)](#23-distributed-idempotency-engine-redis-setnx--hash-storage)
   - [2.4 Transactional Outbox Pattern & At-Least-Once Delivery](#24-transactional-outbox-pattern--at-least-once-delivery)
   - [2.5 Distributed Saga Pattern & Automated Compensating Rollbacks](#25-distributed-saga-pattern--automated-compensating-rollbacks)
   - [2.6 Circuit Breakers & Dead Letter Queue (DLQ) Replay Semantics](#26-circuit-breakers--dead-letter-queue-dlq-replay-semantics)
   - [2.7 Distributed Cron Clustering via ShedLock](#27-distributed-cron-clustering-via-shedlock)
   - [2.8 Refresh Token Rotation (RTR) & Token Family Breach Detection](#28-refresh-token-rotation-rtr--token-family-breach-detection)
   - [2.9 Computer Vision Biometrics: ArcFace 512-D & MiniFASNet](#29-computer-vision-biometrics-arcface-512-d--minifasnet)
3. [Technical Flow 1: Authentication, Token Lifecycle & RTR Breach Detection](#3-technical-flow-1-authentication-token-lifecycle--rtr-breach-detection)
   - [3.1 Sequence Diagram: Happy Path Login & Refresh Token Rotation](#31-sequence-diagram-happy-path-login--refresh-token-rotation)
   - [3.2 Sequence Diagram: Negative Path — Replayed Token Breach & Session Revocation](#32-sequence-diagram-negative-path--replayed-token-breach--session-revocation)
4. [Technical Flow 2: Mobile e-KYC Identity Verification Pipeline](#4-technical-flow-2-mobile-e-kyc-identity-verification-pipeline)
   - [4.1 Sequence Diagram: Happy Path SAS Intent, OCR & ArcFace Face Match](#41-sequence-diagram-happy-path-sas-intent-ocr--arcface-face-match)
   - [4.2 Sequence Diagram: Negative Path — Biometric Spoof & Mismatch Rejection](#42-sequence-diagram-negative-path--biometric-spoof--mismatch-rejection)
5. [Technical Flow 3: Real-Time Multi-Stage Fraud & NLP Risk Scoring](#5-technical-flow-3-real-time-multi-stage-fraud--nlp-risk-scoring)
   - [5.1 Sequence Diagram: Happy Path Sub-2ms Real-Time Clearance](#51-sequence-diagram-happy-path-sub-2ms-real-time-clearance)
   - [5.2 Sequence Diagram: Negative Path — Scam Memo Friction Dialog & Step-Up 2FA](#52-sequence-diagram-negative-path--scam-memo-friction-dialog--step-up-2fa)
6. [Technical Flow 4: Intra-Bank Funds Transfer via Temenos OFS & Outbox](#6-technical-flow-4-intra-bank-funds-transfer-via-temenos-ofs--outbox)
   - [6.1 Sequence Diagram: Happy Path Atomic Ledger Posting & Kafka Emission](#61-sequence-diagram-happy-path-atomic-ledger-posting--kafka-emission)
   - [6.2 Sequence Diagram: Negative Paths — Overdraft & Routing Rejections](#62-sequence-diagram-negative-paths--overdraft--routing-rejections)
7. [Technical Flow 5: Amount Holds & Provisional Reservations (`AC.LOCKED.EVENTS`)](#7-technical-flow-5-amount-holds--provisional-reservations-aclockedevents)
   - [7.1 Sequence Diagram: Anti-Scam Cooling-Off Hold Placement & Capture](#71-sequence-diagram-anti-scam-cooling-off-hold-placement--capture)
   - [7.2 Sequence Diagram: Negative Path — Void / Automated Expiry Sweep](#72-sequence-diagram-negative-path--void--automated-expiry-sweep)
8. [Technical Flow 6: Maker-Checker Transaction Reversal & Beneficiary Lien](#8-technical-flow-6-maker-checker-transaction-reversal--beneficiary-lien)
   - [8.1 Sequence Diagram: Happy Path Maker Ticket, Lien & Checker Compensating Journal](#81-sequence-diagram-happy-path-maker-ticket-lien--checker-compensating-journal)
   - [8.2 Sequence Diagram: Negative Path — Segregation of Duties Violation (400 Bad Request)](#82-sequence-diagram-negative-path--segregation-of-duties-violation-400-bad-request)
9. [Technical Flow 7: Interbank Outward & Inward Wire Settlement (Bilateral Nostro)](#9-technical-flow-7-interbank-outward--inward-wire-settlement-bilateral-nostro)
   - [9.1 Sequence Diagram: Outward Wire Happy Path Settlement](#91-sequence-diagram-outward-wire-happy-path-settlement)
   - [9.2 Sequence Diagram: Outward Wire Partner Timeout & Automated Saga Rollback](#92-sequence-diagram-outward-wire-partner-timeout--automated-saga-rollback)
   - [9.3 Sequence Diagram: Inward Wire Ingestion & Cryptographic Key Validation](#93-sequence-diagram-inward-wire-ingestion--cryptographic-key-validation)
10. [Technical Flow 8: Failed Transaction Resilience, Circuit Breaker & DLQ Recovery](#10-technical-flow-8-failed-transaction-resilience-circuit-breaker--dlq-recovery)
    - [10.1 Sequence Diagram: Circuit Breaker Trip, DLQ Offload & Autonomous Replay](#101-sequence-diagram-circuit-breaker-trip-dlq-offload--autonomous-replay)
11. [Technical Flow 9: End-of-Day (EOD) / Close of Business (COB) Batch Engine](#11-technical-flow-9-end-of-day-eod--close-of-business-cob-batch-engine)
    - [11.1 Sequence Diagram: Full 6-Phase COB Orchestration Sequence](#111-sequence-diagram-full-6-phase-cob-orchestration-sequence)
    - [11.2 Sequence Diagram: Negative Path — GL Trial Balance Out-of-Balance Abort](#112-sequence-diagram-negative-path--gl-trial-balance-out-of-balance-abort)
12. [Technical Flow 10: Regulatory Compliance, CTR Streaming & Audit Vault](#12-technical-flow-10-regulatory-compliance-ctr-streaming--audit-vault)
    - [12.1 Sequence Diagram: AMLA Covered Transaction Ingestion & Tamper-Proof Audit](#121-sequence-diagram-amla-covered-transaction-ingestion--tamper-proof-audit)

---

## 1. Architectural Topology & Microservice Infrastructure

### 1.1 Physical & Logical System Topology

The platform enforces a zero-trust network boundary between public presentation channels, the application gateway perimeter, asynchronous event buses, and the authoritative core banking enclave:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PRESENTATION LAYER                              │
│         React 18 Admin/Teller Web Portal   |   Flutter Mobile App       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTPS / WSS / JWT
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   PERIMETER & EDGE GATEWAY TIER                        │
│                     Spring Cloud Gateway (:8080)                       │
│    ├── Perimeter JWT Signature Validation                              │
│    ├── Token Bucket Rate Limiting                                      │
│    └── Redis Session / Revocation Cache (:6379)                        │
└──────────┬────────────────────────┬──────────────────────────┬─────────┘
           │                        │                          │
           ▼                        ▼                          ▼
┌────────────────────┐   ┌──────────────────────┐   ┌────────────────────┐
│  Account Service   │   │Transfer Orchestrator │   │    Risk Engine     │
│ Spring Boot :8081  │   │  Spring Boot :8082   │   │  FastAPI / Python  │
│ Auth, KYC, Profile │   │Flow & OFS Translation│   │    Port :8084      │
└──────────┬─────────┘   └──────────┬───────────┘   └──────────┬─────────┘
           │                        │                          │
           │                        │ Synchronous HTTP/OFS Wire│
           │                        ▼                          │
           │             ┌──────────────────────┐              │
           │             │     T24 Mock CBS     │              │
           │             │  Spring Boot :8085   │              │
           │             │Authoritative Enclave │              │
           │             └──────────┬───────────┘              │
           │                        │ Exclusive JDBC           │
           │                        ▼                          │
           │             ┌──────────────────────┐              │
           └────────────►│  Azure SQL Database  │◄─────────────┘
                         │     Port :1433       │
                         │Master Ledgers/Outbox │
                         └──────────┬───────────┘
                                    │ Direct Outbox Publish
                                    ▼
                         ┌──────────────────────┐
                         │     Apache Kafka     │
                         │      Port :9092      │
                         │Event Bus & DLQ Topics│
                         └──────────┬───────────┘
                                    │ Event Streaming
                     ┌──────────────┴──────────────┐
                     ▼                             ▼
       ┌───────────────────────────┐ ┌───────────────────────────┐
       │   Notification Service    │ │    Compliance Service     │
       │     Spring Boot :8083     │ │     Spring Boot :8086     │
       │   MailHog / SSE Emitter   │ │  Audit Worker & DLQ Replay│
       └───────────────────────────┘ └─────────────┬─────────────┘
                                                   │
                                                   ▼
                                     ┌───────────────────────────┐
                                     │  PostgreSQL Audit Vault   │
                                     │         Port :5432        │
                                     │  Immutable Append-Only Log│
                                     └───────────────────────────┘
```

### 1.2 Microservice Component Matrix & Port Allocations

| Service Identifier | Technology Stack | Runtime Port | Primary Architectural Responsibilities |
| :--- | :--- | :--- | :--- |
| **API Gateway** (`gateway`) | Spring Boot 3, Spring Cloud Gateway, Netty | `8080` | Perimeter TLS termination, JWT token validation, distributed rate-limiting, CORS handling, and dynamic routing. |
| **Account Service** (`account-service`) | Spring Boot 3, Spring Data JPA, HikariCP | `8081` | User registration, password hashing (BCrypt strength 10), Refresh Token Rotation, 12-digit account provisioning, balance inquiries via cache-aside. |
| **Transfer Orchestrator** (`transfer-orchestrator`) | Spring Boot 3, Resilience4j, Spring WebClient | `8082` | Distributed idempotency locks (`SETNX`), synchronous Risk screening orchestration, 2FA step-up enforcement, Temenos OFS wire serialization, Saga rollback compensation, and DLQ routing. |
| **Notification Service** (`notification-service`) | Spring Boot 3, Kafka Consumer, JavaMailSender | `8083` | Ingests `banking.transfers.events` from Kafka, renders responsive HTML transaction receipts, delivers emails via MailHog SMTP, and streams Server-Sent Events (SSE). |
| **Risk Engine / Laya Engine** (`risk-service`) | Python 3.11, FastAPI, XGBoost, PyTorch, PaddleOCR | `8084` | Sub-2ms Gate 0 deterministic rules, Stage A tabular XGBoost fraud model, Stage B non-autoregressive transformer memo evaluation (Taglish NLP), e-KYC face matching (ArcFace), and liveness detection. |
| **T24 Mock CBS** (`t24-mock-cbs`) | Spring Boot 3, Spring JDBC, HikariCP, Kafka Producer | `8085` / `8087` | **The Authoritative Core Banking Enclave**: Exclusive connection to Azure SQL Master database, OFS parsing, pessimistic row-level locking (`UPDLOCK`), double-entry balance mutation, transactional outbox logging, and COB batch execution. |
| **Compliance & Reporting** (`compliance-service`) | Spring Boot 3, ShedLock, Spring Data JPA | `8086` | Ingests domain events and DLQ incidents, writes immutable audit trails to PostgreSQL, compiles AMLA CTR/SAR filings, and executes the autonomous ShedLock DLQ replay daemon. |

### 1.3 Multi-Tier Data Storage Architecture

1. **Azure SQL Database (`sqldb-master` :1433)**: Master operational ledger storing relational entities: `auth_identity.users`, `core.accounts`, `core.balance_master`, `core.transactions`, `core.gl_ledger`, `core.amount_holds`, `core.reversal_requests`, `core.system_dates`, `core.participant_banks`, and `integration.outbox_events`.
2. **Azure PostgreSQL Database (`audit-vault` :5432)**: Append-only, tamper-proof compliance store maintaining `ledger_mutation_audit`, `dlq_incident_audit`, and regulatory AMLA filings. Direct `UPDATE` and `DELETE` SQL privileges are strictly revoked at the database role level.
3. **Redis (`redis` :6379)**: In-memory datastore for token blacklists, refresh token family lineage sets, distributed idempotency locks (`idemp:<key>`), and balance enquiry cache-aside entries (`balance:<account_id>`, TTL 30s).
4. **Azurite / Azure Blob Storage (:10000)**: Secure private object container (`kyc-vault`) for encrypted front/back government ID scans and biometric selfie videos accessed exclusively via 300-second Shared Access Signature (SAS) tokens.
5. **Apache Kafka (:9092)**: High-throughput distributed event log partitioned across core topics:
   - `banking.transfers.events`: Authoritative ledger execution events (`TransferExecutedEvent`, `TransferReversedEvent`, `AmountHoldPlacedEvent`).
   - `banking.batch.events`: Close-of-business lifecycle notifications (`CobCompletedEvent`, `InterestCapitalizedEvent`).
   - `banking.transfers.dlq`: Failed and retried transactions requiring audit vault persistence or operator intervention.

---

## 2. Advanced Technical Concepts & Protocols Explained

### 2.1 Temenos Open Financial Services (OFS) Wire Protocol
Temenos Core Banking engines do not communicate using generic REST JSON. They utilize a proprietary, high-density comma-delimited wire syntax known as **OFS (Open Financial Services)**. The Transfer Orchestrator serializes internal Java request models into OFS strings prior to transmission over internal REST/TCP sockets:

#### Syntax Specification:
`OPERATION,TRANSACTION.TYPE/PROCESS_MODE/VERSION//REFERENCE_ID,CREDENTIALS,FIELD.NAME:SUBFIELD=VALUE,...`

#### Practical Wire Payloads in AuraBank:
- **Intra-Bank Funds Transfer**:
  ```text
  FUNDS.TRANSFER,AUTH/I/PROCESS//FT26095A8812,OPERATOR01/P@ssw0rd1,TRANSACTION.TYPE=AC,DEBIT.ACCT.NO=1000-2000-3001,CREDIT.ACCT.NO=1000-2000-3002,AMOUNT=100.00,CURRENCY=PHP,MEMO=Retail Payment
  ```
- **Amount Hold Placement (`AC.LOCKED.EVENTS`)**:
  ```text
  AC.LOCKED.EVENTS,INPUT/I/PROCESS//HLD8829104,OPERATOR01/P@ssw0rd1,ACCOUNT.NUMBER=1000-2000-3001,HOLD.AMOUNT=300000.00,EXPIRY.DATE=20261015,HOLD.REASON=ANTI_SCAM_COOLING_OFF
  ```
- **Close-of-Business Cutoff (`BATCH.JOB`)**:
  ```text
  BATCH.JOB,CUTOFF/I/PROCESS//COB-20261007,SYSTEM/SUPERVISOR,JOB.NAME=EOD.POSTING.CUTOFF,ACTION=CLOSE_POSTING_WINDOW
  ```

---

### 2.2 Concurrency & Concurrency Isolation: `UPDLOCK, ROWLOCK`
To prevent the catastrophic **Lost Update Problem** (where two simultaneous requests read balance ₱1,000, both subtract ₱800, and leave ₱200 instead of rejecting the second for overdraft), the T24 Mock CBS executes an explicit pessimistic row lock in Azure SQL:

```sql
SELECT balance_amount, hold_amount 
FROM core.balance_master WITH (UPDLOCK, ROWLOCK)
WHERE account_id = @AccountId;
```

- `ROWLOCK`: Instructs the query optimizer to take fine-grained locks strictly on the target account's leaf page rows, avoiding full-table lock escalation.
- `UPDLOCK`: Places an Update Lock immediately during the `SELECT` phase. Other concurrent transactions attempting to read with `UPDLOCK` are forced to queue until the enclosing transaction commits or rolls back.
- **Deadlock Avoidance**: When locking multiple accounts (Source and Destination in a transfer), the application sorts `account_id` strings lexicographically (`account_a.compareTo(account_b)`) and acquires locks in deterministic sorted order.

---

### 2.3 Distributed Idempotency Engine (Redis `SETNX` & Hash Storage)
AuraBank provides a mathematical guarantee of **Zero Double-Debit** through a two-phase distributed lock protocol:

1. **Phase 1: Ingress Acquisition**:
   The client passes `X-Idempotency-Key: IDEMP-TXN-9912`. The Transfer Orchestrator executes:
   ```text
   SET idemp:IDEMP-TXN-9912 "PROCESSING" EX 120 NX
   ```
   - If Redis returns `nil` (key already exists), the engine queries the associated cached receipt. If processing is still underway, it returns `409 Conflict: Concurrent Request in Progress`. If already finished, it returns the stored `200 OK` JSON response directly without touching the database.
   - If Redis returns `OK`, this worker has acquired the exclusive execution lease.
2. **Phase 2: Post-Commit Finalization**:
   Upon receiving confirmation from T24 CBS, the orchestrator updates Redis:
   ```text
   SETEX idemp:IDEMP-TXN-9912 86400 '{"transactionId":"TXN-9912","status":"Posted","amount":100.00}'
   ```
   The idempotency key is preserved for 24 hours (86,400 seconds).

---

### 2.4 Transactional Outbox Pattern & At-Least-Once Delivery
Directly publishing to Apache Kafka inside a database transaction is a severe anti-pattern (if the database commits but Kafka crashes, data diverges; if Kafka succeeds but the database rolls back, downstream systems process phantom money).

AuraBank uses the **Transactional Outbox Pattern**:
1. Within the exact same local ACID transaction as the account balance debit and credit, the CBS writes an entry into `integration.outbox_events`:
   ```sql
   INSERT INTO integration.outbox_events 
   (event_id, aggregate_type, aggregate_id, event_type, payload, status)
   VALUES (@EventId, 'TRANSFER', @TxId, 'TransferExecutedEvent', @JsonPayload, 'PENDING');
   ```
2. The database transaction commits atomically.
3. An internal event publisher dispatcher reads `PENDING` outbox records, dispatches them to Apache Kafka (`banking.transfers.events`), and marks `status = 'PUBLISHED'` and `processed_at = SYSDATETIMEOFFSET()`.

---

### 2.5 Distributed Saga Pattern & Automated Compensating Rollbacks
In interbank transfers across autonomous banking organizations, two-phase commit (2PC) is impossible. AuraBank implements an **Orchestrated Saga Pattern**:

```
[Local Ledger Debit & Nostro Buffer Credit] 
                 │
                 ├── (Success) ──► [Dispatch HTTP Wire to External Partner Bank]
                 │                                │
                 │                                ├── (Partner 200 OK) ──► [Finalize: Mark SETTLED]
                 │                                │
                 └── (Timeout / Remote 5xx) ──────┴──────────────────────► [Automated Compensating Rollback:
                                                                            Reverse Local Debit, 
                                                                            Credit Customer, 
                                                                            Mark CANCELLED]
```

---

### 2.6 Circuit Breakers & Dead Letter Queue (DLQ) Replay Semantics
If the Core Banking System experiences unrecoverable latency or database lock exhaustion, cascading system collapse is prevented via Resilience4j Circuit Breakers:
- **Failure Rate Threshold**: 50% failures over a sliding window of 10 requests.
- **Circuit State Transition**: Transitions from `CLOSED` to `OPEN`. All incoming calls immediately fail fast with `503 Service Unavailable`.
- **DLQ Offloading**: In-flight transactions that exhaust 3 exponential backoff attempts (100ms, 200ms, 400ms) are diverted to Kafka topic `banking.transfers.dlq`.
- **Constrained Replay**: The Compliance Service's background daemon or human operators can trigger replay requests via `POST /api/v1/cbs/audit/failed-transactions/{id}/resolve`. Replays enforce the original idempotency key to prevent double debiting.

---

### 2.7 Distributed Cron Clustering via ShedLock
When multiple pods of `compliance-service` run concurrently in Kubernetes, running batch jobs (such as DLQ auto-recovery or EOD reconciliation) without coordination would cause duplicate executions.

AuraBank uses **ShedLock**:
```sql
CREATE TABLE shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until DATETIMEOFFSET NOT NULL,
    locked_at DATETIMEOFFSET NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
```
Before executing a batch task, a pod attempts an atomic update on `shedlock`. Only the single pod that wins the database lock executes the batch; all other pods safely skip the execution cycle.

---

### 2.8 Refresh Token Rotation (RTR) & Token Family Breach Detection
To prevent compromised long-lived refresh tokens from allowing infinite unauthorized access, AuraBank implements **Refresh Token Rotation (RTR)**:
1. Every time a refresh token is used, it is **invalidated** and replaced with a brand-new refresh token.
2. Tokens are grouped into a cryptographic **Token Family** stored in Redis: `family:<user_id>:<family_id>`.
3. **Breach Detection**: If an attacker steals a previously used refresh token and attempts to replay it, the server detects that the token was already consumed. This signals that a token theft occurred. The server immediately **revokes the entire token family**, destroying all active sessions for that user across all devices.

---

### 2.9 Computer Vision Biometrics: ArcFace 512-D & MiniFASNet
The e-KYC pipeline utilizes state-of-the-art neural computer vision models:
- **MiniFASNet (Facial Anti-Spoofing Network)**: Analyzes spatial frequency distributions and depth cues in the selfie video to identify printed paper masks, silicone cutouts, and AMOLED display replays.
- **ArcFace (Additive Angular Margin Loss)**: Maps cropped facial images into a hyperspherical embedding space ($\mathbb{R}^{512}$). The distance between the ID photo vector $\vec{v}_{\text{id}}$ and the selfie vector $\vec{v}_{\text{selfie}}$ is calculated using Cosine Distance:
  $$\cos(\theta) = \frac{\vec{v}_{\text{id}} \cdot \vec{v}_{\text{selfie}}}{\|\vec{v}_{\text{id}}\| \|\vec{v}_{\text{selfie}}\|}$$
  If $\cos(\theta) \ge 0.60$ (equivalent to an 80% biometric confidence threshold), the customer's identity is mathematically verified.

---

## 3. Technical Flow 1: Authentication, Token Lifecycle & RTR Breach Detection

### 3.1 Sequence Diagram: Happy Path Login & Refresh Token Rotation

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (Web/Mobile)
    participant GW as API Gateway (:8080)
    participant AS as Account Service (:8081)
    participant R as Redis Cache (:6379)
    participant DB as Azure SQL (:1433)

    Note over C,DB: 1. Initial Authentication & Session Generation
    C->>GW: POST /api/v1/auth/login<br/>{email, password, deviceType: "WEB"}
    GW->>AS: Forward Login Request
    AS->>DB: SELECT * FROM auth_identity.users WHERE email = ?
    DB-->>AS: User record (password_hash)
    AS->>AS: BCrypt.checkpw(password, hash)
    AS->>AS: Generate Token Family ID: fam-9988<br/>Issue Access Token (TTL: 15m)<br/>Issue Refresh Token RT-1 (TTL: 7d)
    AS->>R: SADD token_family:usr-1001:fam-9988 "RT-1"<br/>SETEX rt:RT-1 604800 "ACTIVE"
    AS-->>GW: 200 OK {access_token, refresh_token: "RT-1"}
    GW-->>C: 200 OK

    Note over C,DB: 2. Refresh Token Rotation (RTR) Lifecycle
    C->>GW: POST /api/v1/auth/refresh<br/>{refresh_token: "RT-1"}
    GW->>AS: Forward Token Refresh
    AS->>R: GET rt:RT-1
    R-->>AS: "ACTIVE"
    AS->>R: SETEX rt:RT-1 86400 "REVOKED"<br/>Issue new Refresh Token "RT-2"<br/>SADD token_family:usr-1001:fam-9988 "RT-2"<br/>SETEX rt:RT-2 604800 "ACTIVE"
    AS-->>GW: 200 OK {access_token: "AT-new", refresh_token: "RT-2"}
    GW-->>C: 200 OK
```

### 3.2 Sequence Diagram: Negative Path — Replayed Token Breach & Session Revocation

```mermaid
sequenceDiagram
    autonumber
    participant Attacker as Malicious Actor
    participant GW as API Gateway (:8080)
    participant AS as Account Service (:8081)
    participant R as Redis Cache (:6379)

    Note over Attacker,R: Attacker replays stolen, already-consumed RT-1
    Attacker->>GW: POST /api/v1/auth/refresh<br/>{refresh_token: "RT-1"}
    GW->>AS: Forward Token Refresh
    AS->>R: GET rt:RT-1
    R-->>AS: "REVOKED"
    
    Note over AS,R: CRITICAL BREACH DETECTED: Token reuse attempt!
    AS->>R: SMEMBERS token_family:usr-1001:fam-9988
    R-->>AS: ["RT-1", "RT-2"]
    AS->>R: DEL rt:RT-1 rt:RT-2<br/>DEL token_family:usr-1001:fam-9988<br/>SETEX blacklist:usr-1001 86400 "BREACH_REVOKED"
    
    AS-->>GW: 401 Unauthorized: Session Hijacking Detected
    GW-->>Attacker: 401 Unauthorized (All user sessions terminated)
```

---

## 4. Technical Flow 2: Mobile e-KYC Identity Verification Pipeline

### 4.1 Sequence Diagram: Happy Path SAS Intent, OCR & ArcFace Face Match

```mermaid
sequenceDiagram
    autonumber
    participant App as Mobile Client (Flutter)
    participant GW as API Gateway (:8080)
    participant AS as Account Service (:8081)
    participant Blob as Azurite Blob Storage (:10000)
    participant Risk as Laya Risk/KYC Engine (:8084)
    participant DB as Azure SQL (:1433)

    App->>GW: POST /api/v1/kyc/upload-intent<br/>{docType: "PASSPORT"}
    GW->>AS: Forward Intent Request
    AS->>Blob: Generate Pre-Signed SAS Write URL (TTL: 300s)
    Blob-->>AS: https://azurite/kyc-vault/doc-99.jpg?sas_token=xyz
    AS-->>App: 200 OK {uploadUrl, fileKey: "doc-99.jpg"}

    App->>Blob: Direct Binary PUT doc-99.jpg (ID Scan & Selfie Video)
    Blob-->>App: 201 Created

    App->>GW: POST /api/v1/kyc/verify<br/>{fileKey: "doc-99.jpg", selfieKey: "selfie-99.mp4"}
    GW->>AS: Forward Verification Request
    AS->>Risk: POST /api/v1/kyc/evaluate<br/>{fileKey, selfieKey}
    
    Risk->>Blob: Download ID & Selfie Binaries
    Risk->>Risk: 1. Run PaddleOCR (Extract Name, DOB, DocID)<br/>2. MiniFASNet Liveness Score: 0.98 (REAL)<br/>3. ArcFace 512-D Embedding Cosine Sim: 0.87 (PASS)
    Risk-->>AS: 200 OK {verdict: "APPROVED", confidence: 0.87}

    AS->>DB: UPDATE auth_identity.users SET status = 'ACTIVE' WHERE user_id = ?<br/>INSERT INTO core.accounts (account_number: "100100001234", type: "SAVINGS")<br/>INSERT INTO core.balance_master (account_id, balance: 0.00, hold: 0.00)
    AS-->>App: 200 OK {status: "VERIFIED", accountNumber: "100100001234"}
```

### 4.2 Sequence Diagram: Negative Path — Biometric Spoof & Mismatch Rejection

```mermaid
sequenceDiagram
    autonumber
    participant App as Mobile Client
    participant AS as Account Service (:8081)
    participant Risk as Laya Risk/KYC Engine (:8084)
    participant DB as Azure SQL (:1433)

    App->>AS: POST /api/v1/kyc/verify (Spoofed Photo of Screen)
    AS->>Risk: POST /api/v1/kyc/evaluate
    Risk->>Risk: MiniFASNet Liveness Analyzer
    Note over Risk: Liveness Score: 0.12 < 0.85 Threshold<br/>(Presentation Attack Detected)
    Risk-->>AS: 400 Bad Request {error: "LIVENESS_FAILED", score: 0.12}

    AS->>DB: INSERT INTO core.kyc_audit_log (user_id, status: 'REJECTED_SPOOF')
    AS-->>App: 400 Bad Request: "Live face verification failed. Ensure real camera usage."
```

---

## 5. Technical Flow 3: Real-Time Multi-Stage Fraud & NLP Risk Scoring

### 5.1 Sequence Diagram: Happy Path Sub-2ms Real-Time Clearance

```mermaid
sequenceDiagram
    autonumber
    participant Orch as Transfer Orchestrator (:8082)
    participant Risk as Python Risk Engine (:8084)
    participant T24 as T24 Mock CBS (:8085)

    Orch->>Risk: POST /api/v1/risk/evaluate<br/>{sourceAccountId, amount: 100.00, memo: "Grocery allowance", ip: "120.29.70.1", deviceId: "DEV-1"}
    
    Note over Risk: Stage A: Tabular XGBoost Inference (< 2ms)<br/>Input: velocity_1h=1, balance_drain=0.01, geo_dist=0km<br/>Probability p = 0.02
    Note over Risk: Stage B: Laya Semantic NLP (< 30ms)<br/>Memo: "Grocery allowance" -> Normal Intent
    Risk-->>Orch: 200 OK {verdict: "ALLOW", riskScore: 0.02, requires2FA: false}

    Orch->>T24: Dispatch OFS Wire: FUNDS.TRANSFER,AUTH/I/PROCESS...
```

### 5.2 Sequence Diagram: Negative Path — Scam Memo Friction Dialog & Step-Up 2FA

```mermaid
sequenceDiagram
    autonumber
    participant C as Mobile Client
    participant Orch as Transfer Orchestrator (:8082)
    participant Risk as Python Risk Engine (:8084)
    participant DB as PostgreSQL Audit Vault (:5432)

    C->>Orch: POST /api/v1/transfers<br/>{amount: 45000.00, memo: "Telegram crypto VIP task investment"}
    Orch->>Risk: POST /api/v1/risk/evaluate
    
    Risk->>Risk: Stage A: Tabular p = 0.15 (ALLOW)<br/>Stage B: Laya NLP parses memo "crypto VIP task"<br/>Decision: SCAM_INVESTMENT (Probability: 0.94)
    Risk->>Risk: Escalate-Only Combiner: max(ALLOW, ADVISORY_WARNING) = ADVISORY_WARNING
    Risk->>DB: INSERT INTO sar_drafts (reason: "SCAM_INVESTMENT_MEMO", amount: 45000.00)
    Risk-->>Orch: 200 OK {verdict: "ADVISORY_WARNING", warningType: "INVESTMENT_SCAM", coolingPeriodSecs: 600}

    Orch-->>C: 409 Conflict / Step-Up Required<br/>{status: "ADVISORY_WARNING", message: "High risk scam memo detected", options: ["CANCEL", "PAUSE_10M", "PROCEED_WITH_SCA"]}
```

---

## 6. Technical Flow 4: Intra-Bank Funds Transfer via Temenos OFS & Outbox

### 6.1 Sequence Diagram: Happy Path Atomic Ledger Posting & Kafka Emission

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (Web/Mobile)
    participant GW as API Gateway (:8080)
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)
    participant K as Apache Kafka (:9092)
    participant Notif as Notification Service (:8083)

    C->>GW: POST /api/v1/transfers<br/>Header X-Idempotency-Key: IDEMP-FT-101<br/>{sourceAccountId: "1000-2000-3001", targetAccountId: "1000-2000-3002", amount: 100.00}
    GW->>Orch: Route Request
    Orch->>Orch: Acquire Redis Idempotency Lock: SETNX idemp:IDEMP-FT-101
    Orch->>Orch: Serialize to Temenos OFS:<br/>FUNDS.TRANSFER,AUTH/I/PROCESS//FT26095A8812...

    Orch->>CBS: POST /api/v1/cbs/funds-transfer (Wire text/plain)
    
    Note over CBS,SQL: BEGIN TRANSACTION (Exclusive ACID Enclave)
    CBS->>SQL: SELECT balance_amount, hold_amount FROM core.balance_master WITH (UPDLOCK, ROWLOCK) WHERE account_id = '1000-2000-3001'
    SQL-->>CBS: balance=25000000.00, hold=0.00 (Solvent)
    CBS->>SQL: UPDATE core.balance_master SET balance_amount -= 100.00 WHERE account_id = '1000-2000-3001'<br/>UPDATE core.balance_master SET balance_amount += 100.00 WHERE account_id = '1000-2000-3002'
    CBS->>SQL: INSERT INTO core.transactions (transaction_id: 'FT26095A8812', status: 'POSTED', amount: 100.00)
    CBS->>SQL: INSERT INTO core.gl_ledger (gl_code: '2100-CUST-LIAB', DR: 100.00, CR: 0.00)<br/>INSERT INTO core.gl_ledger (gl_code: '2100-CUST-LIAB', DR: 0.00, CR: 100.00)
    CBS->>SQL: INSERT INTO integration.outbox_events (event_type: 'TransferExecutedEvent', status: 'PENDING')
    Note over CBS,SQL: COMMIT TRANSACTION
    
    CBS->>K: Direct Publish TransferExecutedEvent to banking.transfers.events
    CBS->>SQL: UPDATE integration.outbox_events SET status = 'PUBLISHED' WHERE event_id = ?
    CBS-->>Orch: Temenos OFS Wire Response:<br/>FT26095A8812//1/SUCCESS
    
    Orch-->>GW: 200 OK {transactionId: "FT26095A8812", status: "Posted"}
    GW-->>C: 200 OK

    K->>Notif: Consume TransferExecutedEvent
    Notif->>Notif: Render HTML receipt & dispatch email via MailHog
```

### 6.2 Sequence Diagram: Negative Paths — Overdraft & Routing Rejections

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)

    Note over C,SQL: Case A: Insufficient Funds Overdraft Rejection
    C->>Orch: POST /api/v1/transfers (Amount: 999999999.00)
    Orch->>CBS: POST /api/v1/cbs/funds-transfer (OFS Payload)
    CBS->>SQL: SELECT balance_amount, hold_amount WITH (UPDLOCK, ROWLOCK)
    SQL-->>CBS: balance=500.00, hold=0.00
    Note over CBS: Solvency Check Failed: 500.00 < 999,999,999.00
    CBS-->>Orch: OFS Rejection: //3/FAILED: INSUFFICIENT_FUNDS
    Orch-->>C: 400 Bad Request: "Insufficient available funds for transfer"

    Note over C,SQL: Case B: Circular Self-Transfer Hygiene Check
    C->>Orch: POST /api/v1/transfers (source == destination: '1000-2000-3001')
    Note over Orch: Ingress Validation: sourceAccountId.equals(targetAccountId)
    Orch-->>C: 400 Bad Request: "Source and destination account must be different"
```

---

## 7. Technical Flow 5: Amount Holds & Provisional Reservations (`AC.LOCKED.EVENTS`)

### 7.1 Sequence Diagram: Anti-Scam Cooling-Off Hold Placement & Capture

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)
    participant K as Apache Kafka (:9092)

    Note over C,K: 1. Place Anti-Scam Cooling-Off Hold (Amount >= ₱250,000)
    C->>Orch: POST /api/v1/transfers {amount: 300000.00, sourceAccountId: "1000-2000-3001"}
    Note over Orch: Amount >= 250,000 PHP: Mandate Cooling-Off Hold (BSP Cir. 1140)
    Orch->>CBS: POST /api/v1/cbs/holds<br/>AC.LOCKED.EVENTS,INPUT/I/PROCESS//HLD-3001,HOLD.AMOUNT=300000.00
    
    CBS->>SQL: BEGIN TRANSACTION<br/>SELECT balance_amount, hold_amount WITH (UPDLOCK, ROWLOCK)<br/>UPDATE core.balance_master SET hold_amount += 300000.00 WHERE account_id = '1000-2000-3001'<br/>INSERT INTO core.amount_holds (hold_id: 'HLD-3001', amount: 300000.00, status: 'ACTIVE')<br/>COMMIT
    CBS->>K: Publish AmountHoldPlacedEvent
    CBS-->>Orch: 200 OK {holdId: "HLD-3001", status: "ACTIVE"}
    Orch-->>C: 200 OK {status: "Reserved", coolingOffRequired: true, lockSeconds: 86400}

    Note over C,K: 2. Capture Hold (Cooling-Off Window Passed / Verified)
    Orch->>CBS: POST /api/v1/cbs/holds/HLD-3001/capture
    CBS->>SQL: BEGIN TRANSACTION<br/>UPDATE core.balance_master SET balance_amount -= 300000.00, hold_amount -= 300000.00 WHERE account_id = '1000-2000-3001'<br/>UPDATE core.balance_master SET balance_amount += 300000.00 WHERE account_id = '1000-2000-3002'<br/>UPDATE core.amount_holds SET status = 'CAPTURED' WHERE hold_id = 'HLD-3001'<br/>COMMIT
    CBS-->>Orch: 200 OK {status: "CAPTURED"}
```

### 7.2 Sequence Diagram: Negative Path — Void / Automated Expiry Sweep

```mermaid
sequenceDiagram
    autonumber
    participant Daemon as Expiry Sweeper Daemon
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)

    Daemon->>CBS: Trigger Scheduled Hold Expiry Sweep
    CBS->>SQL: SELECT * FROM core.amount_holds WHERE status = 'ACTIVE' AND expiry_date < SYSUTCDATETIME()
    SQL-->>CBS: [Hold 'HLD-EXPIRED', account_id: '1000-2000-3001', amount: 5000.00]
    
    CBS->>SQL: BEGIN TRANSACTION<br/>UPDATE core.balance_master SET hold_amount -= 5000.00 WHERE account_id = '1000-2000-3001'<br/>UPDATE core.amount_holds SET status = 'RELEASED_EXPIRED' WHERE hold_id = 'HLD-EXPIRED'<br/>COMMIT
    Note over CBS,SQL: Available balance restored to customer automatically
```

---

## 8. Technical Flow 6: Maker-Checker Transaction Reversal & Beneficiary Lien

### 8.1 Sequence Diagram: Happy Path Maker Ticket, Lien & Checker Compensating Journal

```mermaid
sequenceDiagram
    autonumber
    participant Maker as Maker (Teller: usr-1003-tel-001)
    participant Checker as Checker (Manager: usr-1004-adm-001)
    participant Orch as Reversal Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)

    Note over Maker,SQL: Step 1: Maker submits reversal dispute ticket
    Maker->>Orch: POST /api/v1/reversals/request<br/>{originalTxId: "TXN-REV-SEED", makerId: "usr-1003-tel-001", reason: "DISPUTE"}
    Orch->>CBS: POST /api/v1/cbs/reversals/ticket
    
    CBS->>SQL: BEGIN TRANSACTION<br/>INSERT INTO core.reversal_requests (ticket_id: 'TKT-881', maker_id: 'usr-1003-tel-001', status: 'PENDING')<br/>-- Place protective beneficiary lien on target account<br/>UPDATE core.balance_master SET hold_amount += 150.00 WHERE account_id = '1000-2000-3002'<br/>COMMIT
    CBS-->>Orch: 200 OK {ticketId: "TKT-881", status: "PENDING"}
    Orch-->>Maker: 200 OK

    Note over Checker,SQL: Step 2: Checker approves ticket & executes compensating double-entry
    Checker->>Orch: POST /api/v1/reversals/approve<br/>{reversalRequestId: "TKT-881", checkerId: "usr-1004-adm-001"}
    Orch->>CBS: POST /api/v1/cbs/reversals/approve
    
    CBS->>SQL: SELECT maker_id FROM core.reversal_requests WHERE ticket_id = 'TKT-881'
    SQL-->>CBS: maker_id = 'usr-1003-tel-001' (Different from checker: PASS)
    
    CBS->>SQL: BEGIN TRANSACTION<br/>-- Release lien and debit target<br/>UPDATE core.balance_master SET balance_amount -= 150.00, hold_amount -= 150.00 WHERE account_id = '1000-2000-3002'<br/>-- Credit refund to original source<br/>UPDATE core.balance_master SET balance_amount += 150.00 WHERE account_id = '1000-2000-3001'<br/>-- Update ticket & link transactions<br/>UPDATE core.reversal_requests SET status = 'APPROVED', checker_id = 'usr-1004-adm-001'<br/>INSERT INTO core.transactions (transaction_id: 'TXN-REV-COMPENSATE', original_tx_id: 'TXN-REV-SEED', status: 'POSTED')<br/>COMMIT
    
    CBS-->>Orch: 200 OK {status: "APPROVED"}
    Orch-->>Checker: 200 OK (Compensating transfer posted)
```

### 8.2 Sequence Diagram: Negative Path — Segregation of Duties Violation (400 Bad Request)

```mermaid
sequenceDiagram
    autonumber
    participant RogueUser as Teller (usr-1003-tel-001)
    participant Orch as Reversal Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)

    RogueUser->>Orch: POST /api/v1/reversals/approve<br/>{reversalRequestId: "TKT-881", checkerId: "usr-1003-tel-001"}
    Orch->>CBS: POST /api/v1/cbs/reversals/approve
    CBS->>SQL: SELECT maker_id FROM core.reversal_requests WHERE ticket_id = 'TKT-881'
    SQL-->>CBS: maker_id = 'usr-1003-tel-001'
    
    Note over CBS: DUAL CONTROL BREACH: maker_id.equals(checker_id)
    CBS-->>Orch: 400 Bad Request: "Maker and Checker cannot be the same user (BSP Circular 982)"
    Orch-->>RogueUser: 400 Bad Request (Action audited and rejected)
```

---

## 9. Technical Flow 7: Interbank Outward & Inward Wire Settlement (Bilateral Nostro)

### 9.1 Sequence Diagram: Outward Wire Happy Path Settlement

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer App
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)
    participant ExtBank as CooBS Partner Bank (:8082)

    C->>Orch: POST /api/v1/transfers<br/>{amount: 1000.00, destinationBank: "COOBS", destinationAccountId: "B-ACC-998877"}
    
    Note over Orch,SQL: Step 1: Quarantine funds in Local Nostro Buffer
    Orch->>CBS: POST /api/v1/cbs/funds-transfer (Debit Customer, Credit Nostro Buffer)
    CBS->>SQL: BEGIN TRANSACTION<br/>UPDATE core.balance_master SET balance_amount -= 1000.00 WHERE account_id = '1000-2000-3001'<br/>UPDATE core.balance_master SET balance_amount += 1000.00 WHERE account_id = 'ACC-NOSTRO-BANK-B'<br/>COMMIT
    CBS-->>Orch: 200 OK (Quarantined)

    Note over Orch,ExtBank: Step 2: Dispatch Wire to External Counterparty
    Orch->>ExtBank: POST /api/v1/mock-partner/transfers<br/>{amount: 1000.00, beneficiaryAccountId: "B-ACC-998877"}
    ExtBank-->>Orch: 200 OK {status: "SETTLED", partnerRef: "B-TXN-OK"}

    Note over Orch,SQL: Step 3: Finalize Transaction
    Orch->>CBS: POST /api/v1/cbs/transactions/finalize
    CBS->>SQL: UPDATE core.transactions SET status = 'POSTED' WHERE transaction_id = ?
    Orch-->>C: 200 OK {status: "Posted", partnerRef: "B-TXN-OK"}
```

### 9.2 Sequence Diagram: Outward Wire Partner Timeout & Automated Saga Rollback

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer App
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)
    participant ExtBank as CooBS Partner Bank

    C->>Orch: POST /api/v1/transfers (Amount: 500.00 to CooBS)
    Orch->>CBS: Debit Local Customer 500.00 / Credit Nostro Buffer 500.00
    CBS->>SQL: Balance Mutated Successfully
    CBS-->>Orch: 200 OK

    Orch->>ExtBank: POST /api/v1/mock-partner/transfers (Socket Hangs...)
    Note over Orch,ExtBank: ReadTimeoutException (5000ms SLA Breached)

    Note over Orch,SQL: AUTOMATED SAGA COMPENSATING ROLLBACK
    Orch->>CBS: POST /api/v1/cbs/funds-transfer (Compensating Refund)
    CBS->>SQL: BEGIN TRANSACTION<br/>UPDATE core.balance_master SET balance_amount -= 500.00 WHERE account_id = 'ACC-NOSTRO-BANK-B'<br/>UPDATE core.balance_master SET balance_amount += 500.00 WHERE account_id = '1000-2000-3001'<br/>UPDATE core.transactions SET status = 'CANCELLED', memo = 'Saga rollback: Partner timeout'<br/>COMMIT
    CBS-->>Orch: 200 OK (Refunded)

    Orch-->>C: 502 Bad Gateway: "We could not confirm the transfer with the partner bank. Your ₱500.00 balance has been automatically restored."
```

### 9.3 Sequence Diagram: Inward Wire Ingestion & Cryptographic Key Validation

```mermaid
sequenceDiagram
    autonumber
    participant ExtBank as CooBS Partner Bank
    participant GW as API Gateway (:8080)
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)

    ExtBank->>GW: POST /api/v1/interbank/transfers/inward<br/>Header X-Interbank-API-Key: AURABANK-SECURE-KEY-2026<br/>{amount: 2500.00, beneficiaryAccountId: "1000-2000-3001"}
    GW->>Orch: Route Request
    
    Orch->>CBS: GET /api/v1/cbs/participant-banks/COOBS
    CBS->>SQL: SELECT api_key, is_active FROM core.participant_banks WHERE bank_code = 'COOBS'
    SQL-->>CBS: api_key='AURABANK-SECURE-KEY-2026', is_active=1
    Note over Orch: Cryptographic Key Matches

    Orch->>CBS: POST /api/v1/cbs/funds-transfer<br/>(Debit Inward Vostro Buffer, Credit Beneficiary 1000-2000-3001)
    CBS->>SQL: BEGIN TRANSACTION<br/>UPDATE core.balance_master SET balance_amount += 2500.00 WHERE account_id = '1000-2000-3001'<br/>COMMIT
    CBS-->>Orch: 200 OK
    Orch-->>ExtBank: 200 OK {status: "ACCEPTED", beneficiaryAccountId: "1000-2000-3001"}
```

---

## 10. Technical Flow 8: Failed Transaction Resilience, Circuit Breaker & DLQ Recovery

### 10.1 Sequence Diagram: Circuit Breaker Trip, DLQ Offload & Autonomous Replay

```mermaid
sequenceDiagram
    autonumber
    participant Orch as Transfer Orchestrator (:8082)
    participant CBS as T24 Mock CBS (:8085)
    participant K as Apache Kafka (:9092)
    participant Comp as Compliance Service (:8086)
    participant PG as PostgreSQL Audit Vault (:5432)
    participant Daemon as Autonomous DLQ Recovery Daemon

    Note over Orch,CBS: CBS experiencing database lock saturation
    Orch->>CBS: Attempt 1: Call CBS -> 504 Timeout
    Orch->>CBS: Attempt 2 (Backoff 100ms) -> 504 Timeout
    Orch->>CBS: Attempt 3 (Backoff 200ms) -> 504 Timeout
    
    Note over Orch: Circuit Breaker Trips to OPEN!
    Orch->>K: Publish TransferFailedToDlqEvent to banking.transfers.dlq
    
    K->>Comp: Consume DLQ Event
    Comp->>PG: INSERT INTO failed_transaction_history (idempotency_key, failure_stage: 'CBS_LOCK_TIMEOUT', status: 'PENDING_REPLAY')

    Note over Daemon,CBS: Scheduled Replay Engine (ShedLock Protected)
    Daemon->>PG: SELECT * FROM failed_transaction_history WHERE status = 'PENDING_REPLAY'
    PG-->>Daemon: [Incident TX-991]
    Daemon->>CBS: GET /actuator/health
    CBS-->>Daemon: 200 OK {status: "UP"}
    
    Daemon->>Orch: POST /api/v1/transfers (Replay with original idempotency key)
    Orch->>CBS: Execute OFS Wire
    CBS-->>Orch: 200 OK
    Orch-->>Daemon: 200 OK
    Daemon->>PG: UPDATE failed_transaction_history SET status = 'RESOLVED' WHERE failure_id = 'TX-991'
```

---

## 11. Technical Flow 9: End-of-Day (EOD) / Close of Business (COB) Batch Engine

### 11.1 Sequence Diagram: Full 6-Phase COB Orchestration Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Cron as K8s CronJob / Operator
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)
    participant K as Apache Kafka (:9092)

    Cron->>CBS: POST /api/v1/cbs/cob/run
    
    Note over CBS,SQL: Phase 1: Posting Cutoff & Balance Freeze
    CBS->>SQL: UPDATE core.system_dates SET posting_window_open = 0, status = 'EOD_CUTOFF'<br/>INSERT INTO core.eod_balance_snapshots (account_id, closing_balance, business_date)<br/>SELECT account_id, balance_amount, '2026-10-07' FROM core.balance_master

    Note over CBS,SQL: Phase 2: Automated Low-ADB Fee Assessment
    CBS->>SQL: -- Evaluate accounts < ₱1,000.00 ADB<br/>-- Apply Zero-Overdraft Arrears rule<br/>UPDATE core.balance_master SET balance_amount -= 100.00 WHERE balance_amount >= 100.00<br/>INSERT INTO core.uncollected_fees (account_id, uncollected_amount) SELECT account_id, 100.00 - balance_amount WHERE balance_amount < 100.00

    Note over CBS,SQL: Phase 3: Daily Interest & 20% BIR Withholding Tax
    CBS->>SQL: -- Compute Daily Accrual: balance * (0.005 / 365)<br/>-- Deduct 20% BIR Tax<br/>INSERT INTO core.interest_accruals (account_id, accrued_amount, tax_withheld, net_accrual)<br/>VALUES (@acc, 13.6986, 2.7397, 10.9589)

    Note over CBS,SQL: Phase 4: General Ledger Trial Balance Reconciliation
    CBS->>SQL: SELECT SUM(debit_amount) AS total_dr, SUM(credit_amount) AS total_cr FROM core.gl_ledger
    SQL-->>CBS: total_dr = 450,000,000.0000, total_cr = 450,000,000.0000 (Variance = 0.0000)

    Note over CBS,SQL: Phase 5: Financial Statement Compilation
    CBS->>SQL: INSERT INTO core.cob_batch_log (business_date: '2026-10-07', status: 'COMPLETED')

    Note over CBS,SQL: Phase 6: Business Date Rollover
    CBS->>SQL: UPDATE core.system_dates SET business_date = '2026-10-08', posting_window_open = 1, status = 'ONLINE'
    
    CBS->>K: Publish EodCompletedEvent to banking.batch.events
    CBS-->>Cron: 200 OK {status: "SUCCESS", newBusinessDate: "2026-10-08"}
```

### 11.2 Sequence Diagram: Negative Path — GL Trial Balance Out-of-Balance Abort

```mermaid
sequenceDiagram
    autonumber
    participant Cron as K8s CronJob
    participant CBS as T24 Mock CBS (:8085)
    participant SQL as Azure SQL (:1433)

    Cron->>CBS: POST /api/v1/cbs/cob/run
    Note over CBS,SQL: Phase 4: GL Trial Balance Execution
    CBS->>SQL: SELECT SUM(debit_amount) AS dr, SUM(credit_amount) AS cr FROM core.gl_ledger
    SQL-->>CBS: dr = 450,000,050.0000, cr = 450,000,000.0000
    
    Note over CBS: CRITICAL INTEGRITY BREACH: Variance = 50.0000 != 0
    CBS->>SQL: UPDATE core.system_dates SET status = 'ERROR_HALTED'<br/>UPDATE core.cob_batch_log SET status = 'FAILED', error_message = 'Trial balance variance detected'
    
    CBS-->>Cron: 500 Internal Server Error: "COB Aborted: General Ledger Trial Balance out of balance by ₱50.0000. System locked in ERROR_HALTED."
```

---

## 12. Technical Flow 10: Regulatory Compliance, CTR Streaming & Audit Vault

### 12.1 Sequence Diagram: AMLA Covered Transaction Ingestion & Tamper-Proof Audit

```mermaid
sequenceDiagram
    autonumber
    participant CBS as T24 Mock CBS (:8085)
    participant K as Apache Kafka (:9092)
    participant Comp as Compliance Service (:8086)
    participant PG as PostgreSQL Audit Vault (:5432)
    participant AMLC as AMLC Regulatory Portal

    CBS->>K: Publish TransferExecutedEvent<br/>{transactionId: "TX-500K", amount: 600000.00, sender: "Juan Dela Cruz"}
    
    K->>Comp: Ingest Event from banking.transfers.events
    
    Note over Comp: Evaluation: Amount (₱600,000.00) >= Threshold (₱500,000.00)
    Comp->>PG: INSERT INTO amla_covered_transactions (tx_id: 'TX-500K', amount: 600000.00, ctr_status: 'QUEUED')
    Comp->>PG: INSERT INTO ledger_mutation_audit (tx_id, payload_hash: SHA256(payload), ingested_at: NOW())
    
    Note over Comp,AMLC: Scheduled AMLC E-Filing Batch
    Comp->>AMLC: POST /regulatory/amla/v2/ctr-submissions (Encrypted XML Payload)
    AMLC-->>Comp: 200 OK {submissionRef: "AMLC-CTR-2026-9021"}
    Comp->>PG: UPDATE amla_covered_transactions SET ctr_status = 'FILED', submission_ref = 'AMLC-CTR-2026-9021'
```
