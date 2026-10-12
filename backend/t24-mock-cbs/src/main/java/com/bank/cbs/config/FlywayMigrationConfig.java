package com.bank.cbs.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Dual-DataSource Flyway Migration Configuration.
 * Orchestrates deterministic migrations independently across:
 * 1. Master Relational Database (Oracle XE 21c / 23ai)
 * 2. Dedicated Immutable Audit Vault (PostgreSQL 16)
 */
@Configuration
public class FlywayMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationConfig.class);

    @Value("${cbs.flyway.baseline-on-migrate:true}")
    private boolean baselineOnMigrate;

    @Value("${cbs.flyway.baseline-version:2}")
    private String baselineVersion;

    @Value("${cbs.flyway.seed-sample-data:true}")
    private boolean seedSampleData;

    @Bean(name = "masterFlyway")
    @ConditionalOnProperty(name = "cbs.flyway.master.enabled", havingValue = "true", matchIfMissing = true)
    public Flyway masterFlyway(@Qualifier("masterDataSource") DataSource dataSource) {
        log.info("Checking Master Database connectivity for Flyway migrations...");
        boolean hasExistingTables = false;
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            log.info("Master Database detected: {}", dbProduct);

            // Skip running Oracle-specific DDL if running against in-memory H2
            if (dbProduct != null && dbProduct.toLowerCase().contains("h2")) {
                log.info("In-memory H2 detected for Master DataSource. Skipping Oracle Flyway migrations.");
                return null;
            }

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM all_tables WHERE owner = 'FSE_USER' AND table_name != 'FLYWAY_SCHEMA_HISTORY'")) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    hasExistingTables = (count > 0);
                    log.info("Detected {} existing non-Flyway tables in FSE_USER schema. hasExistingTables={}", count, hasExistingTables);
                }
            }
        } catch (Exception e) {
            log.warn("Could not probe Master Database metadata: {}. Proceeding with migration attempt.", e.getMessage());
        }

        List<String> locations = new ArrayList<>();
        locations.add("classpath:db/migration/oracle");
        if (seedSampleData) {
            log.info("Development sample data seeding enabled (classpath:db/seed/oracle)");
            locations.add("classpath:db/seed/oracle");
        }

        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(new String[0]))
                .table("FLYWAY_SCHEMA_HISTORY");

        if (hasExistingTables && baselineOnMigrate) {
            log.info("Existing schema detected. Enabling baseline at version {}", baselineVersion);
            config.baselineOnMigrate(true).baselineVersion(baselineVersion);
        } else {
            log.info("Fresh database detected (or baseline disabled). Migrations will execute from V1 without baselining.");
            config.baselineOnMigrate(false);
        }

        Flyway flyway = config.load();

        try {
            log.info("Running Flyway repair on Master Database...");
            flyway.repair();
        } catch (Exception e) {
            log.warn("Flyway repair on Master Database encountered notice: {}", e.getMessage());
        }

        log.info("Executing Flyway migrations on Master Database...");
        flyway.migrate();
        log.info("Flyway migrations on Master Database completed successfully.");
        return flyway;
    }

    @Bean(name = "auditFlyway")
    @ConditionalOnProperty(name = "cbs.flyway.audit.enabled", havingValue = "true", matchIfMissing = true)
    public Flyway auditFlyway(@Qualifier("auditDataSource") DataSource dataSource) {
        log.info("Checking Audit Database connectivity for Flyway migrations...");
        boolean hasExistingTables = false;
        try (Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            log.info("Audit Database detected: {}", dbProduct);

            // Skip running Postgres-specific DDL if running against in-memory H2
            if (dbProduct != null && dbProduct.toLowerCase().contains("h2")) {
                log.info("In-memory H2 detected for Audit DataSource. Skipping Postgres Flyway migrations.");
                return null;
            }

            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name != 'flyway_schema_history'")) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    hasExistingTables = (count > 0);
                    log.info("Detected {} existing non-Flyway tables in PostgreSQL public schema. hasExistingTables={}", count, hasExistingTables);
                }
            }
        } catch (Exception e) {
            log.warn("Could not probe Audit Database metadata: {}. Proceeding with migration attempt.", e.getMessage());
        }

        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgres")
                .table("flyway_schema_history");

        if (hasExistingTables && baselineOnMigrate) {
            log.info("Existing PostgreSQL audit schema detected. Enabling baseline at version {}", baselineVersion);
            config.baselineOnMigrate(true).baselineVersion(baselineVersion);
        } else {
            log.info("Fresh PostgreSQL database detected (or baseline disabled). Migrations will execute from V1 without baselining.");
            config.baselineOnMigrate(false);
        }

        Flyway flyway = config.load();

        try {
            log.info("Running Flyway repair on Audit Database...");
            flyway.repair();
        } catch (Exception e) {
            log.warn("Flyway repair on Audit Database encountered notice: {}", e.getMessage());
        }

        log.info("Executing Flyway migrations on Audit Database...");
        flyway.migrate();
        log.info("Flyway migrations on Audit Database completed successfully.");
        return flyway;
    }
}
