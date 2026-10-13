package com.bank.cbs.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.postgresql.ds.PGSimpleDataSource;
import oracle.jdbc.pool.OracleDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FlywayMigrationIntegrationTest {

    private static final String ORACLE_URL = "jdbc:oracle:thin:@localhost:1521/XEPDB1";
    private static final String ORACLE_USER = "fse_user";
    private static final String ORACLE_PASS = "fse_password";

    private static final String PG_URL = "jdbc:postgresql://localhost:5433/banking_audit";
    private static final String PG_USER = "audit_user";
    private static final String PG_PASS = "audit_password";

    @Test
    @Order(1)
    @DisplayName("Master Database (Oracle XE): Apply all migrations & dev seeds idempotently")
    void testOracleFlywayMigrations() throws SQLException {
        OracleDataSource oracleDs = new OracleDataSource();
        oracleDs.setURL(ORACLE_URL);
        oracleDs.setUser(ORACLE_USER);
        oracleDs.setPassword(ORACLE_PASS);

        boolean oracleAvailable = false;
        try (Connection conn = oracleDs.getConnection()) {
            oracleAvailable = (conn != null);
        } catch (Exception e) {
            System.out.println("Oracle XE not reachable on localhost:1521: " + e.getMessage());
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(oracleAvailable, "Oracle XE must be reachable on localhost:1521");

        Flyway flyway = Flyway.configure()
                .dataSource(oracleDs)
                .locations("classpath:db/migration/oracle", "classpath:db/seed/oracle")
                .table("FLYWAY_SCHEMA_HISTORY")
                .baselineOnMigrate(false)
                .load();

        MigrateResult result = flyway.migrate();
        assertTrue(result.success, "Flyway master migration must succeed");
        System.out.println("Applied Oracle migrations: " + result.migrationsExecuted);

        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 5, "At least V1, V2, V3, V4, R, V99 must be applied. Actual count: " + applied.length);

        for (MigrationInfo info : applied) {
            System.out.println("Oracle Migration: " + info.getVersion() + " - " + info.getDescription() + " [" + info.getState() + "]");
            assertTrue(info.getState().isApplied(), "Migration " + info.getScript() + " should be applied");
        }

        // Validate table existence in Oracle
        List<String> expectedTables = List.of(
                "USERS", "ACCOUNTS", "BALANCE_MASTER", "TRANSACTIONS",
                "OUTBOX_EVENTS", "NOTIFICATIONS", "KYC_SUBMISSIONS", "DEVICE_PUSH_TOKENS",
                "GL_ACCOUNTS", "GL_LEDGER", "GL_BALANCES", "REVERSAL_REQUESTS",
                "UNCOLLECTED_FEES", "INTEREST_ACCRUALS", "EOD_BALANCE_SNAPSHOTS",
                "SYSTEM_DATES", "COB_BATCH_LOG", "TRANSACTION_STATUS_HISTORY",
                "PARTICIPANT_BANKS", "ADMIN_USERS"
        );

        try (Connection conn = oracleDs.getConnection();
             Statement stmt = conn.createStatement()) {

            List<String> actualTables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("SELECT table_name FROM user_tables")) {
                while (rs.next()) {
                    actualTables.add(rs.getString("table_name").toUpperCase());
                }
            }

            for (String expected : expectedTables) {
                assertTrue(actualTables.contains(expected), "Missing expected Oracle table: " + expected);
            }

            // Check view v_all_users
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM v_all_users")) {
                assertTrue(rs.next());
                int count = rs.getInt(1);
                assertTrue(count > 0, "v_all_users should return seeded users and admin users. Found: " + count);
            }

            // Check participant banks seed
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM participant_banks WHERE is_active = 1")) {
                assertTrue(rs.next());
                int pbCount = rs.getInt(1);
                assertTrue(pbCount >= 2, "Expected at least 2 active participant banks. Found: " + pbCount);
            }
        }

        // Idempotency check: Re-running migrate must execute 0 new migrations and succeed
        MigrateResult secondRun = flyway.migrate();
        assertTrue(secondRun.success, "Subsequent migration run must succeed");
        assertEquals(0, secondRun.migrationsExecuted, "No pending migrations should execute on secondary run");
    }

    @Test
    @Order(2)
    @DisplayName("Audit Vault (PostgreSQL 16): Apply all migrations, V3 DLQ index & immutability triggers")
    void testPostgresFlywayMigrations() throws SQLException {
        PGSimpleDataSource pgDs = new PGSimpleDataSource();
        pgDs.setUrl(PG_URL);
        pgDs.setUser(PG_USER);
        pgDs.setPassword(PG_PASS);

        boolean pgAvailable = false;
        try (Connection conn = pgDs.getConnection()) {
            pgAvailable = (conn != null);
        } catch (Exception e) {
            System.out.println("PostgreSQL Audit Vault not reachable on localhost:5433: " + e.getMessage());
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(pgAvailable, "PostgreSQL Audit Vault must be reachable on localhost:5433");

        Flyway flyway = Flyway.configure()
                .dataSource(pgDs)
                .locations("classpath:db/migration/postgres")
                .table("flyway_schema_history")
                .baselineOnMigrate(false)
                .load();

        MigrateResult result = flyway.migrate();
        assertTrue(result.success, "Flyway audit migration must succeed");
        System.out.println("Applied PostgreSQL migrations: " + result.migrationsExecuted);

        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 3, "At least V1, V2, V3, R must be applied. Actual count: " + applied.length);

        for (MigrationInfo info : applied) {
            System.out.println("Postgres Migration: " + info.getVersion() + " - " + info.getDescription() + " [" + info.getState() + "]");
            assertTrue(info.getState().isApplied(), "Migration " + info.getScript() + " should be applied");
        }

        // Verify tables exist in PostgreSQL
        List<String> expectedTables = List.of(
                "ledger_mutation_audit",
                "failed_transaction_audit",
                "eod_reports_metadata",
                "compliance_filings",
                "transaction_status_audit",
                "audit_block_anchor"
        );

        try (Connection conn = pgDs.getConnection();
             Statement stmt = conn.createStatement()) {

            List<String> actualTables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
                while (rs.next()) {
                    actualTables.add(rs.getString("table_name").toLowerCase());
                }
            }

            for (String expected : expectedTables) {
                assertTrue(actualTables.contains(expected), "Missing expected PostgreSQL table: " + expected);
            }

            // Verify V3 composite index exists on failed_transaction_audit
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'failed_transaction_audit' AND indexname = 'idx_failed_tx_replay_status_time'")) {
                assertTrue(rs.next(), "Composite index idx_failed_tx_replay_status_time from V3 must exist");
            }

            // Verify Immutability Trigger (R__enforce_audit_immutability)
            // Insert test ledger mutation
            stmt.executeUpdate(
                    "INSERT INTO ledger_mutation_audit (transaction_id, account_id, mutation_type, mutation_amount, before_balance, after_balance, initiator_user_id) " +
                    "VALUES ('tx-immutability-test-1', 'acc-test-1', 'DEBIT', 100.0000, 1000.0000, 900.0000, 'usr-test-1') " +
                    "ON CONFLICT DO NOTHING"
            );

            // Attempting to UPDATE must be blocked by the trigger
            SQLException updateEx = assertThrows(SQLException.class, () -> {
                stmt.executeUpdate("UPDATE ledger_mutation_audit SET after_balance = 800.0000 WHERE transaction_id = 'tx-immutability-test-1'");
            }, "UPDATE on ledger_mutation_audit must throw compliance violation");
            assertTrue(updateEx.getMessage().contains("Compliance Violation"), "Error must contain 'Compliance Violation'");

            // Attempting to DELETE must also be blocked by the trigger
            SQLException deleteEx = assertThrows(SQLException.class, () -> {
                stmt.executeUpdate("DELETE FROM ledger_mutation_audit WHERE transaction_id = 'tx-immutability-test-1'");
            }, "DELETE on ledger_mutation_audit must throw compliance violation");
            assertTrue(deleteEx.getMessage().contains("Compliance Violation"), "Error must contain 'Compliance Violation'");
        }

        // Idempotency check: Re-running migrate must execute 0 new migrations and succeed
        MigrateResult secondRun = flyway.migrate();
        assertTrue(secondRun.success, "Subsequent PostgreSQL migration run must succeed");
        assertEquals(0, secondRun.migrationsExecuted, "No pending migrations should execute on secondary run");
    }
}
