package com.bank.cbs;

import com.bank.cbs.controller.CbsUsageController;
import com.bank.cbs.entity.master.TransactionMaster;
import com.bank.cbs.repository.master.TransactionMasterRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature: kyc-tiered-transfer-limits, Property 3: Used amount counts only outgoing counted
 * transfers in the window.
 *
 * Runs the real {@link TransactionMasterRepository#sumOutgoing} JPQL against an in-memory H2 database
 * and compares it with an in-memory oracle over seeded random rows.
 *
 * **Validates: Requirements 3.2, 3.3, 3.4, 3.5**
 */
@SpringJUnitConfig(CbsUsageQueryPropertyTest.TestJpaConfig.class)
class CbsUsageQueryPropertyTest {

    private static final int ITERATIONS = 150;

    /** Independent of the production constant on purpose: this is the spec (design.md, Property 3). */
    private static final Set<String> COUNTED = Set.of(
            "INITIATED", "AUTHORIZED", "RESERVED", "PROCESSING", "POSTED", "PENDINGREVERSAL",
            "PENDING_APPROVAL", "COMMITTED");

    private static final String[] TYPES = {"TRANSFER", "TRANSFER", "TRANSFER", "REVERSAL", "DEPOSIT", "FEE"};
    private static final String[] STATUSES = {
            "Initiated", "Authorized", "Reserved", "Processing", "Posted", "PendingReversal",
            "Failed", "Cancelled", "Reversed",
            "PENDING_APPROVAL", "COMMITTED", "FAILED", "REJECTED"};
    private static final String[] OTHER_ACCOUNTS = {"ACC-X1", "ACC-X2", "ACC-X3"};

    // Windows are [start, end). Day lies inside the month.
    private static final Instant DAY_START = Instant.parse("2026-03-10T16:00:00Z");
    private static final Instant DAY_END = DAY_START.plusSeconds(24 * 3600);
    private static final Instant MONTH_START = Instant.parse("2026-02-28T16:00:00Z");
    private static final Instant MONTH_END = Instant.parse("2026-03-31T16:00:00Z");

    private static final Instant[] EDGE_TIMES = {
            DAY_START, DAY_START.minusNanos(1), DAY_START.plusNanos(1),
            DAY_END, DAY_END.minusNanos(1), DAY_END.plusNanos(1),
            MONTH_START, MONTH_START.minusNanos(1), MONTH_START.plusNanos(1),
            MONTH_END, MONTH_END.minusNanos(1), MONTH_END.plusNanos(1),
            Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-04-15T00:00:00Z")};

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(
            basePackageClasses = TransactionMasterRepository.class,
            includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TransactionMasterRepository.class))
    static class TestJpaConfig {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    "jdbc:h2:mem:cbs_usage_test;DB_CLOSE_DELAY=-1", "sa", "");
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS core");
            // created_at keeps nanosecond precision so one-nano edge cases are meaningful.
            jdbc.execute("CREATE TABLE IF NOT EXISTS core.transactions ("
                    + "transaction_id VARCHAR(64) PRIMARY KEY, idempotency_key VARCHAR(64) UNIQUE, "
                    + "from_account_id VARCHAR(64), to_account_id VARCHAR(64), "
                    + "amount DECIMAL(18,4) NOT NULL, before_balance DECIMAL(18,4), after_balance DECIMAL(18,4), "
                    + "currency VARCHAR(3), type VARCHAR(30), transaction_type VARCHAR(20), "
                    + "status VARCHAR(30) NOT NULL, requires_maker_checker INT, approved_by VARCHAR(64), "
                    + "memo VARCHAR(255), created_at TIMESTAMP(9) WITH TIME ZONE NOT NULL, "
                    + "updated_at TIMESTAMP(9) WITH TIME ZONE NOT NULL)");
            return ds;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setManagedTypes(PersistenceManagedTypes.of(TransactionMaster.class.getName()));
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties props = new Properties();
            props.setProperty("hibernate.hbm2ddl.auto", "none");
            emf.setJpaProperties(props);
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    @Autowired
    private TransactionMasterRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        repository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Property 3: sumOutgoing equals the in-memory oracle for day and month windows")
    void sumOutgoingMatchesOracle() {
        Random random = new Random(0);
        for (int i = 0; i < ITERATIONS; i++) {
            repository.deleteAllInBatch();
            List<String> customer = customerAccounts(random);
            List<TransactionMaster> rows = randomRows(random, customer, i);
            repository.saveAll(rows);

            for (Instant[] window : new Instant[][]{{DAY_START, DAY_END}, {MONTH_START, MONTH_END}}) {
                BigDecimal expected = oracle(rows, customer, window[0], window[1]);
                BigDecimal actual = repository.sumOutgoing(
                        customer, TransactionMasterRepository.COUNTED_TRANSFER_STATUSES, window[0], window[1]);
                assertNotNull(actual, "iteration " + i + ": sum must never be null");
                assertEquals(0, expected.compareTo(actual),
                        "iteration " + i + " window " + window[0] + ".." + window[1]
                                + " expected " + expected + " but was " + actual);
            }
        }
    }

    @Test
    @DisplayName("sumOutgoing returns zero (not null) when no rows match")
    void sumOutgoingZeroWhenNoRows() {
        BigDecimal actual = repository.sumOutgoing(
                List.of("ACC-NONE"), TransactionMasterRepository.COUNTED_TRANSFER_STATUSES, DAY_START, DAY_END);
        assertNotNull(actual);
        assertEquals(0, BigDecimal.ZERO.compareTo(actual));
    }

    @Test
    @DisplayName("GET /api/v1/cbs/usage/outgoing returns daily_used and monthly_used matching the oracle")
    void usageEndpointMatchesOracle() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CbsUsageController(repository)).build();
        Random random = new Random(0);
        for (int i = 0; i < 30; i++) {
            repository.deleteAllInBatch();
            List<String> customer = customerAccounts(random);
            List<TransactionMaster> rows = randomRows(random, customer, i);
            repository.saveAll(rows);

            String body = mvc.perform(get("/api/v1/cbs/usage/outgoing")
                            .param("accountIds", String.join(",", customer))
                            .param("dayStart", DAY_START.toString())
                            .param("dayEnd", DAY_END.toString())
                            .param("monthStart", MONTH_START.toString())
                            .param("monthEnd", MONTH_END.toString()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            Map<String, BigDecimal> json = objectMapper.readValue(body, new TypeReference<>() {});
            assertEquals(0, oracle(rows, customer, DAY_START, DAY_END).compareTo(json.get("daily_used")),
                    "iteration " + i + " daily_used in " + body);
            assertEquals(0, oracle(rows, customer, MONTH_START, MONTH_END).compareTo(json.get("monthly_used")),
                    "iteration " + i + " monthly_used in " + body);
        }
    }

    @Test
    @DisplayName("GET /api/v1/cbs/usage/outgoing with empty accountIds returns zeros")
    void usageEndpointEmptyAccountIdsReturnsZeros() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CbsUsageController(repository)).build();
        repository.save(row("T-1", "ACC-X1", "TRANSFER", "Posted", "100.0000", DAY_START.plusSeconds(60)));

        String body = mvc.perform(get("/api/v1/cbs/usage/outgoing")
                        .param("accountIds", "")
                        .param("dayStart", DAY_START.toString())
                        .param("dayEnd", DAY_END.toString())
                        .param("monthStart", MONTH_START.toString())
                        .param("monthEnd", MONTH_END.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, BigDecimal> json = objectMapper.readValue(body, new TypeReference<>() {});
        assertEquals(0, BigDecimal.ZERO.compareTo(json.get("daily_used")), body);
        assertEquals(0, BigDecimal.ZERO.compareTo(json.get("monthly_used")), body);
    }

    // ---- helpers ----

    private static List<String> customerAccounts(Random random) {
        int n = 1 + random.nextInt(3);
        List<String> ids = new ArrayList<>();
        for (int k = 1; k <= n; k++) {
            ids.add("ACC-C" + k);
        }
        return ids;
    }

    private static List<TransactionMaster> randomRows(Random random, List<String> customer, int iteration) {
        int count = 5 + random.nextInt(21);
        List<TransactionMaster> rows = new ArrayList<>();
        for (int r = 0; r < count; r++) {
            String source;
            int pick = random.nextInt(10);
            if (pick < 5) {
                source = customer.get(random.nextInt(customer.size()));
            } else if (pick < 9) {
                source = OTHER_ACCOUNTS[random.nextInt(OTHER_ACCOUNTS.length)];
            } else {
                source = null;
            }
            String status = STATUSES[random.nextInt(STATUSES.length)];
            switch (random.nextInt(3)) {
                case 0 -> status = status.toUpperCase(Locale.ROOT);
                case 1 -> status = status.toLowerCase(Locale.ROOT);
                default -> { /* keep original mixed case */ }
            }
            Instant createdAt;
            if (random.nextInt(3) == 0) {
                createdAt = EDGE_TIMES[random.nextInt(EDGE_TIMES.length)];
            } else {
                long span = MONTH_END.getEpochSecond() - MONTH_START.getEpochSecond();
                createdAt = MONTH_START.plusSeconds(random.nextLong(span));
            }
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(10_000_000), 4);
            TransactionMaster tx = row("T-" + iteration + "-" + r, source,
                    TYPES[random.nextInt(TYPES.length)], status, amount.toPlainString(), createdAt);
            // Half of the non-customer rows are incoming to the customer: must still not count.
            if (!customer.contains(source) && random.nextBoolean()) {
                tx.setTargetAccountId(customer.get(0));
            }
            rows.add(tx);
        }
        return rows;
    }

    private static TransactionMaster row(String id, String source, String type, String status,
                                         String amount, Instant createdAt) {
        return TransactionMaster.builder()
                .transactionId(id)
                .sourceAccountId(source)
                .targetAccountId("ACC-TARGET")
                .type(type)
                .status(status)
                .amount(new BigDecimal(amount))
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private static BigDecimal oracle(List<TransactionMaster> rows, List<String> customer,
                                     Instant from, Instant to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (TransactionMaster t : rows) {
            boolean counted = t.getSourceAccountId() != null
                    && customer.contains(t.getSourceAccountId())
                    && "TRANSFER".equals(t.getType())
                    && COUNTED.contains(t.getStatus().toUpperCase(Locale.ROOT))
                    && !t.getCreatedAt().isBefore(from)
                    && t.getCreatedAt().isBefore(to);
            if (counted) {
                sum = sum.add(t.getAmount());
            }
        }
        return sum;
    }
}
