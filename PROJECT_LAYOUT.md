# Project Layout & Repository Structure

This document outlines the multi-module microservice directory structure, configuration layout, and infrastructure deployment files for the project.

```
FSE-Capstone/
├── .github/
│   └── workflows/
│       └── ci.yml                          # Continuous Integration pipeline (Build, Unit Test, SonarQube)
├── backend/
│   ├── pom.xml                             # Root Maven aggregator POM
│   ├── common-contracts/                   # Shared DTOs, Enums, and Exception classes
│   │   ├── pom.xml
│   │   └── src/main/java/com/fse/banking/common/
│   │       ├── dto/                        # MutationRequest, TransferRequest, ProblemDetails
│   │       ├── enums/                      # MutationType, AccountType, TransferStatus, Role
│   │       └── exception/                  # LedgerPersistenceException, InsufficientFundsException
│   ├── gateway-service/                    # Spring Cloud Gateway & Perimeter Security
│   │   ├── pom.xml
│   │   └── src/main/
│   │       ├── java/com/fse/banking/gateway/
│   │       │   ├── config/                 # SecurityConfig, RouteConfig, RedisRateLimiterConfig
│   │       │   └── filter/                 # JwtAuthenticationFilter, TokenBlacklistFilter
│   │       └── resources/
│   │           └── application.yml
│   ├── account-service/                    # Customer Lifecycle, KYC, & Account Provisioning
│   │   ├── pom.xml
│   │   └── src/main/
│   │       ├── java/com/fse/banking/account/
│   │       │   ├── controller/             # AuthController, CustomerController, AccountController
│   │       │   ├── model/                  # User, Role, Account
│   │       │   ├── repository/             # UserRepository, AccountRepository
│   │       │   └── service/                # KycService, AccountProvisioningService, AuthService
│   │       └── resources/
│   │           └── application.yml
│   ├── ledger-mutation-engine/             # Core Concurrency Engine & Dual-Write Storage
│   │   ├── pom.xml
│   │   └── src/main/
│   │       ├── java/com/fse/banking/ledger/
│   │       │   ├── config/                 # PrimaryOracleDataSourceConfig, AuditPostgresDataSourceConfig, HikariProps
│   │       │   ├── controller/             # MutationController, TransferController, BillsPaymentController
│   │       │   ├── exception/              # GlobalControllerAdvice (RFC-7807 handler)
│   │       │   ├── model/
│   │       │   │   ├── master/             # CustomerBalanceMaster, Transfer, BillsPayment (Oracle JPA entities)
│   │       │   │   └── audit/              # LedgerMutationAudit, SecurityAuditLog (Postgres JPA entities)
│   │       │   ├── repository/
│   │       │   │   ├── master/             # BalanceMasterRepository (@Lock PESSIMISTIC_WRITE), TransferRepository
│   │       │   │   └── audit/              # LedgerMutationAuditRepository (Append-only)
│   │       │   ├── service/                # DualWriteMutationService, MakerCheckerWorkflowService, OutboxPublisherService
│   │       │   └── messaging/              # KafkaProducerConfig, TransferEventProducer, TransferSagaConsumer (@KafkaListener)
│   │       └── resources/
│   │           ├── application.yml         # HikariCP pool sizes (max=30, idle=5), Kafka topic configs
│   │           └── db/migration/           # Flyway scripts for Oracle (including outbox_events) and Postgres schemas
│   └── notification-service/               # Asynchronous Transaction Alert Consumer
│       ├── pom.xml
│       └── src/main/
│           ├── java/com/fse/banking/notification/
│           │   ├── consumer/               # TransactionEventConsumer (@KafkaListener)
│           │   └── service/                # EmailReceiptService, PushAlertService
│           └── resources/
│               └── application.yml
├── frontend/                               # Retail Banking Web Portal (Single Page Application)
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── tailwind.config.js
│   └── src/
│       ├── assets/
│       ├── components/
│       │   ├── common/                     # Navbar, Modal, CurrencyInput, StatusBadge
│       │   ├── customer/                   # BalanceCard, TransferForm, TransactionHistoryTable, BillsPaymentModal
│       │   ├── ledger/                     # PendingReviewQueue, ApprovalDialog, RejectionDialog
│       │   └── admin/                      # KycReviewTable, CreateAccountModal, AuditInspector
│       ├── context/                        # AuthContext (JWT storage, role checking)
│       ├── pages/                          # LoginPage, CustomerDashboard, TellerReviewPage, AdminDashboard
│       └── services/                       # apiService (Axios with Bearer token interceptor)
├── infrastructure/                         # Containerized Orchestration & DB Initialization
│   ├── docker-compose.yml                  # Full stack runner (Databases, Broker, Services, Monitoring)
│   ├── adminer/
│   │   └── Dockerfile                      # Custom Adminer image with Oracle Instant Client & OCI8
│   ├── oracle/
│   │   └── init.sql                        # Master tables DDL & constraints
│   ├── postgres/
│   │   └── init.sql                        # Immutable audit tables DDL & trigger blocks
│   ├── prometheus/
│   │   └── prometheus.yml                  # Scrape configurations for microservice Actuator endpoints
│   └── grafana/
│       └── provisioning/                   # Pre-configured Grafana dashboards (TPS, latency, JVM GC)
├── testing/                                # Stress Testing & Automated Verification Suites
│   ├── jmeter/
│   │   ├── double_spend_stress_test.jmx    # 50 concurrent threads race-condition test plan
│   │   └── throughput_benchmark.jmx        # 200+ TPS sustained volume validation
│   └── postman/
│       └── banking_api_collection.json     # E2E functional test suite
├── architecture.html                       # Explorable standalone HTML architecture diagram (Archify)
├── architecture.json                       # Archify JSON specification (Showcase quality passed)
├── ARCHITECTURE.md                         # Detailed system architecture document
├── API_SPECIFICATION.md                    # REST API endpoints, JSR-380 validation & RFC-7807 specs
├── ERD.md                                  # Complete Entity-Relationship specifications and DDL
├── JIRA_BACKLOG.md                         # JIRA Backlog with Epics, Stories, Acceptance Criteria & Points
├── jira_backlog_fse_capstone.xlsx          # Formatted Excel Backlog spreadsheet
└── generate_backlog_excel.py               # Generator script for Excel workbook
```
