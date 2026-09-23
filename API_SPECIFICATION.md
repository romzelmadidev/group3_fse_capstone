# REST API Specification & Endpoint Contracts

This document specifies the complete REST API catalog, JSR-380 validation boundaries, and RFC-7807 problem details for the Retail Ledger & Balance Mutation Engine.

An interactive Archify request lifecycle diagram is delivered at [`api_sequence.html`](file:///c:/Users/JLB83807/The%20Vault/workspaces/FSE-Capstone/api_sequence.html).

---

## 1. Global Perimeter Standards

- **Base Path**: `/api/v1`
- **Security**: Bearer JWT passed via `Authorization: Bearer <token>`
- **Idempotency**: All mutating operations (`POST`, `PATCH`) accept `X-Idempotency-Key: <UUID>`
- **Numeric Precision**: Financial amounts strictly enforce `@Digits(integer=14, fraction=4)` and `@Positive`
- **Currency**: Fixed to `PHP`

---

## 2. Global Error Format (RFC-7807 Problem Details)

All validation faults, business rule rejections, and infrastructure exceptions return standard Problem Details JSON:

```json
{
  "type": "https://api.banking.capstone/errors/validation-failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "The payload failed JSR-380 perimeter validation constraints.",
  "instance": "/api/v1/ledger/mutate",
  "timestamp": "2026-09-22T05:50:00Z",
  "invalid_params": [
    {
      "field": "mutation_amount",
      "rejected_value": -500.0000,
      "reason": "must be greater than 0"
    },
    {
      "field": "mutation_amount",
      "rejected_value": 12.12345,
      "reason": "numeric value out of bounds (<14 digits>.<4 digits> expected)"
    }
  ]
}
```

---

## 3. Complete API Endpoint Catalog

### Module 1: Authentication & Identity Management

| Method | Endpoint | Authorized Roles | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/auth/register` | Public | Customer registration with KYC details |
| `POST` | `/api/v1/auth/login` | Public | Authenticates credentials; sets refresh cookie and returns short-lived access JWT |
| `POST` | `/api/v1/auth/refresh` | Public / Token Holder | Exchanges single-use refresh token cookie for new access token and rotated refresh token |
| `POST` | `/api/v1/auth/logout` | All Roles | Blacklists access token, deletes token family from Redis, and clears refresh cookie |

#### A. Customer Registration
`POST /api/v1/auth/register`
```json
// Request Body
{
  "email": "juan.delacruz@example.ph",
  "password": "SecurePassword123!",
  "first_name": "Juan",
  "last_name": "Dela Cruz",
  "middle_name": "Santos",
  "date_of_birth": "1992-05-14",
  "phone_number": "+639171234567",
  "address_line": "123 Ayala Avenue, Makati City",
  "government_id_type": "PASSPORT",
  "government_id_number": "P9921840A"
}

// Response (201 Created)
{
  "user_id": "USR-882190",
  "email": "juan.delacruz@example.ph",
  "kyc_status": "PENDING",
  "created_at": "2026-09-22T05:50:00Z"
}
```

#### B. User Login
`POST /api/v1/auth/login`
```json
// Request Body
{
  "email": "juan.delacruz@example.ph",
  "password": "SecurePassword123!"
}

// Response Headers
Set-Cookie: refresh_token=rt_9f8c2b4e8a1d0f3c; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800

// Response Body (200 OK)
{
  "access_token": "eyJhbGciOiJIUzI1NiIsIn...",
  "token_type": "Bearer",
  "expires_in_seconds": 900,
  "role": "ROLE_CUSTOMER",
  "user_id": "USR-882190"
}
```

#### C. Token Refresh (Refresh Token Rotation)
`POST /api/v1/auth/refresh`
```json
// Request Headers (Browser automatically attaches HttpOnly cookie)
Cookie: refresh_token=rt_9f8c2b4e8a1d0f3c

// Response Headers (Rotated Single-Use Refresh Token)
Set-Cookie: refresh_token=rt_1a4e7f9c2d5b8e0a; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=604800

// Response Body (200 OK)
{
  "access_token": "eyJhbGciOiJIUzI1NiIsIn...",
  "token_type": "Bearer",
  "expires_in_seconds": 900
}

// Error Response: Replay Attack / Token Reuse Detected (401 Unauthorized)
// RFC-7807 Problem Details; all tokens in session family are revoked instantly in Redis
{
  "type": "https://api.retailbank.ph/errors/token-breach-detected",
  "title": "Token Replay Breach Detected",
  "status": 401,
  "detail": "Revoked refresh token presented. Entire session family has been terminated for security.",
  "instance": "/api/v1/auth/refresh",
  "timestamp": "2026-09-22T05:51:00Z"
}
```

#### D. User Logout
`POST /api/v1/auth/logout`
```json
// Request Headers
Authorization: Bearer eyJhbGciOiJIUzI1NiIsIn...
Cookie: refresh_token=rt_1a4e7f9c2d5b8e0a

// Response Headers (Clears cookie)
Set-Cookie: refresh_token=; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=0

// Response Body (200 OK)
{
  "message": "Session terminated successfully. Access token blacklisted and token family revoked."
}
```

---

### Module 2: Account Lifecycle & Balance Operations

| Method | Endpoint | Authorized Roles | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/accounts` | Customer | Lists all accounts owned by current user |
| `GET` | `/api/v1/accounts/{account_id}/balance` | Customer, Ledger, Admin | Retrieves balance breakdown (30s Redis cache) |
| `POST` | `/api/v1/accounts` | Admin | Provisions a new account (`SAVINGS`, `CHECKING`, `CREDIT`) |
| `PATCH` | `/api/v1/accounts/{account_id}/status` | Admin | Locks, unlocks, or closes an account |

#### A. Balance Inquiry (Cached in Redis)
`GET /api/v1/accounts/{account_id}/balance`
```json
// Response (200 OK)
{
  "account_id": "ACC-1002938471",
  "account_type": "SAVINGS",
  "currency": "PHP",
  "current_balance": 25000000.0000,
  "held_balance": 15000000.0000,
  "available_balance": 10000000.0000,
  "status": "ACTIVE",
  "cached": true,
  "last_updated": "2026-09-22T05:50:00Z"
}
```

#### B. Account Provisioning
`POST /api/v1/accounts`
```json
// Request Body
{
  "user_id": "USR-882190",
  "account_type": "SAVINGS",
  "initial_deposit": 5000.0000,
  "currency": "PHP"
}

// Response (201 Created)
{
  "account_id": "ACC-1002938471",
  "user_id": "USR-882190",
  "account_type": "SAVINGS",
  "status": "ACTIVE",
  "initial_balance": 5000.0000,
  "created_at": "2026-09-22T05:50:00Z"
}
```

---

### Module 3: Core Balance Mutation Engine

| Method | Endpoint | Authorized Roles | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/ledger/mutate` | System, Ledger | Direct balance debit/credit under `@Lock(PESSIMISTIC_WRITE)` |

#### Direct Mutation Request
`POST /api/v1/ledger/mutate`
```json
// Headers:
// Authorization: Bearer <JWT>
// X-Idempotency-Key: 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d

// Request Body
{
  "transaction_id": "TX-8921-A98F",
  "account_id": "ACC-1002938471",
  "mutation_type": "DEBIT",
  "mutation_amount": 50000.0000,
  "currency": "PHP",
  "reference_note": "Direct terminal settlement"
}

// Response (200 OK)
{
  "transaction_id": "TX-8921-A98F",
  "account_id": "ACC-1002938471",
  "mutation_type": "DEBIT",
  "mutation_amount": 50000.0000,
  "previous_balance": 25000000.0000,
  "new_balance": 24950000.0000,
  "currency": "PHP",
  "audit_receipt_id": 98210,
  "settled_at": "2026-09-22T05:50:02Z"
}
```

---

### Module 4: Funds Transfer & Maker-Checker Workflow

| Method | Endpoint | Authorized Roles | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/transfers` | Customer | Initiates transfer; auto-routes to hold if > 10M PHP |
| `GET` | `/api/v1/transfers/pending` | Ledger (Teller) | Lists high-value transfers awaiting approval |
| `POST` | `/api/v1/transfers/{id}/approve` | Ledger (Teller) | Approves transfer; settles balances & releases hold |
| `POST` | `/api/v1/transfers/{id}/reject` | Ledger (Teller) | Rejects transfer; releases soft hold on source account |
| `GET` | `/api/v1/transfers/{id}` | Customer, Ledger | Retrieves transfer status and execution timestamps |

#### A. Transfer Initiation
`POST /api/v1/transfers`
```json
// Request Body
{
  "source_account_id": "ACC-1002938471",
  "destination_account_id": "ACC-2009847192",
  "transfer_type": "SAME_BANK",
  "destination_bank_code": "THIS_BANK",
  "amount": 15000000.0000,
  "currency": "PHP",
  "remarks": "Corporate dividend disbursement"
}

// Response (202 Accepted - Ingested via Transactional Outbox)
{
  "transfer_id": "TRX-4491-0021",
  "status": "INITIATED",
  "amount": 15000000.0000,
  "currency": "PHP",
  "maker_user_id": "USR-882190",
  "source_account_id": "ACC-1002938471",
  "destination_account_id": "ACC-2009847192",
  "status_url": "/api/v1/transfers/TRX-4491-0021",
  "created_at": "2026-09-22T05:50:05Z"
}
```

#### B. Transfer Status Inquiry (Polling / Verification)
`GET /api/v1/transfers/{id}`
```json
// Response (200 OK - After Kafka Partition Settlement)
{
  "transfer_id": "TRX-4491-0021",
  "status": "EXECUTED",
  "amount": 15000000.0000,
  "currency": "PHP",
  "source_account_id": "ACC-1002938471",
  "destination_account_id": "ACC-2009847192",
  "audit_receipt_id": 98214,
  "settled_at": "2026-09-22T05:50:08Z"
}
```

#### C. Pending Review Queue
`GET /api/v1/transfers/pending`
```json
// Response (200 OK)
[
  {
    "transfer_id": "TRX-4491-0021",
    "amount": 15000000.0000,
    "currency": "PHP",
    "source_account_id": "ACC-1002938471",
    "source_customer_name": "Juan Santos Dela Cruz",
    "destination_account_id": "ACC-2009847192",
    "maker_user_id": "USR-882190",
    "status": "PENDING_APPROVAL",
    "created_at": "2026-09-22T05:50:05Z"
  }
]
```

#### C. Checker Approval
`POST /api/v1/transfers/TRX-4491-0021/approve`
```json
// Request Body
{
  "approval_notes": "Verified board resolution and KYC tier"
}

// Response (200 OK)
{
  "transfer_id": "TRX-4491-0021",
  "status": "EXECUTED",
  "amount": 15000000.0000,
  "checker_user_id": "USR-TELLER-02",
  "audit_receipt_id": 98214,
  "settled_at": "2026-09-22T05:50:10Z"
}
```

---

### Module 5: Bills Payment

| Method | Endpoint | Authorized Roles | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/bills/billers` | Customer | Lists available utility and merchant billers |
| `POST` | `/api/v1/bills/pay` | Customer | Executes atomic bill payment debit |

`POST /api/v1/bills/pay`
```json
// Request Body
{
  "source_account_id": "ACC-1002938471",
  "biller_code": "MERALCO_ELECTRIC",
  "subscriber_account_no": "9021884712",
  "amount": 8450.5000,
  "currency": "PHP"
}

// Response (200 OK)
{
  "payment_id": "PAY-882190",
  "biller_code": "MERALCO_ELECTRIC",
  "amount": 8450.5000,
  "reference_no": "REF-MERALCO-2026-98124",
  "status": "SUCCESS",
  "settled_at": "2026-09-22T05:50:12Z"
}
```

---

### Module 6: Audit & Telemetry Operations

| Method | Endpoint | Authorized Roles | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/audit/account/{account_id}` | Ledger, Admin | Queries immutable PostgreSQL audit journal |
| `GET` | `/actuator/prometheus` | Internal / Prometheus | Prometheus metrics scrape endpoint |
| `GET` | `/actuator/health` | Public | Kubernetes / Docker liveness & readiness check |
