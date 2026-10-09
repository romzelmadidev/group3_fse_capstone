package com.bank.compliance.service;

import com.bank.compliance.generator.AmlaCtrGenerator;
import com.bank.compliance.generator.BirTaxCertificateGenerator;
import com.bank.compliance.generator.CustomerStatementPdfGenerator;
import com.bank.compliance.generator.GlTrialBalanceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class ComplianceReportSeedService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComplianceReportSeedService.class);

    private final AzuriteBlobStorageService azuriteService;
    private final BirTaxCertificateGenerator birGenerator;
    private final GlTrialBalanceGenerator glGenerator;
    private final CustomerStatementPdfGenerator statementGenerator;
    private final AmlaCtrGenerator amlaCtrGenerator;

    public ComplianceReportSeedService(
            AzuriteBlobStorageService azuriteService,
            BirTaxCertificateGenerator birGenerator,
            GlTrialBalanceGenerator glGenerator,
            CustomerStatementPdfGenerator statementGenerator,
            AmlaCtrGenerator amlaCtrGenerator) {
        this.azuriteService = azuriteService;
        this.birGenerator = birGenerator;
        this.glGenerator = glGenerator;
        this.statementGenerator = statementGenerator;
        this.amlaCtrGenerator = amlaCtrGenerator;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Azurite report automatic startup seeding disabled for clean test slate.");
    }

    public synchronized void seedDefaultReportsIfMissing() {
        try {
            log.info("Checking and seeding Azurite compliance reports...");
            LocalDate d = LocalDate.of(2026, 10, 8);

            // 1. BIR 2306 Withholding Certificate PDF
            String birBlob1 = "eod/2026-10-08/bir_2306_withholding_2026-10-08.pdf";
            String birBlob2 = "eod/bir-2306-2026-10-08.pdf";
            if (!azuriteService.exists(birBlob1) || !azuriteService.exists(birBlob2)) {
                byte[] birPdf = birGenerator.generateBir2306Certificate(
                        "2026", d.withDayOfMonth(1), d,
                        "000-111-222-000", "Universal Retail Bank",
                        "999-888-777-000", "Aggregate Retail Depositors",
                        new BigDecimal("50000.00"), new BigDecimal("10000.00")
                );
                if (!azuriteService.exists(birBlob1)) {
                    azuriteService.uploadArtifact(birBlob1, birPdf, "application/pdf");
                }
                if (!azuriteService.exists(birBlob2)) {
                    azuriteService.uploadArtifact(birBlob2, birPdf, "application/pdf");
                }
            }

            // 2. GL Trial Balance Excel & PDF
            String glXlsx1 = "eod/2026-10-08/amla_ctr_filing_2026-10-08.xlsx";
            String glXlsx2 = "eod/gl-trial-balance-2026-10-08.xlsx";
            String glPdf = "eod/2026-10-08/gl_eod_reconciliation_2026-10-08.pdf";

            List<GlTrialBalanceGenerator.GlEntry> entries = List.of(
                    new GlTrialBalanceGenerator.GlEntry("10100", "Cash on Hand", "ASSET", new BigDecimal("15000000.00"), BigDecimal.ZERO),
                    new GlTrialBalanceGenerator.GlEntry("10200", "Due from BSP", "ASSET", new BigDecimal("25000000.00"), BigDecimal.ZERO),
                    new GlTrialBalanceGenerator.GlEntry("20100", "Demand Deposits", "LIABILITY", BigDecimal.ZERO, new BigDecimal("40000000.00")),
                    new GlTrialBalanceGenerator.GlEntry("40100", "Fee Income", "REVENUE", BigDecimal.ZERO, new BigDecimal("500000.00")),
                    new GlTrialBalanceGenerator.GlEntry("50100", "Interest Expense", "EXPENSE", new BigDecimal("500000.00"), BigDecimal.ZERO)
            );

            if (!azuriteService.exists(glXlsx1) || !azuriteService.exists(glXlsx2)) {
                byte[] excelBytes = glGenerator.generateExcel(d, entries);
                if (!azuriteService.exists(glXlsx1)) {
                    azuriteService.uploadArtifact(glXlsx1, excelBytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                }
                if (!azuriteService.exists(glXlsx2)) {
                    azuriteService.uploadArtifact(glXlsx2, excelBytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                }
            }

            if (!azuriteService.exists(glPdf)) {
                byte[] glPdfBytes = glGenerator.generatePdf(d, entries);
                azuriteService.uploadArtifact(glPdf, glPdfBytes, "application/pdf");
            }

            // 3. Customer Statement PDF
            String stmtBlob = "statements/ACC-1001/statement-2026-10-08.pdf";
            if (!azuriteService.exists(stmtBlob)) {
                List<Map<String, Object>> txs = List.of(
                        Map.of("date", "2026-10-01", "description", "Payroll Credit - Direct Deposit", "type", "CREDIT", "amount", "85000.00"),
                        Map.of("date", "2026-10-03", "description", "InstaPay Transfer to BDO", "type", "DEBIT", "amount", "5000.00"),
                        Map.of("date", "2026-10-06", "description", "Bills Payment - Meralco", "type", "DEBIT", "amount", "4250.00"),
                        Map.of("date", "2026-10-08", "description", "Interest Credit (Net of 20% Tax)", "type", "CREDIT", "amount", "125.50")
                );
                byte[] stmtBytes = statementGenerator.generateStatement(
                        "ACC-1001", "1001-2200-3344", new BigDecimal("8500000.00"),
                        d.minusDays(30), d, txs
                );
                azuriteService.uploadArtifact(stmtBlob, stmtBytes, "application/pdf");
            }

            // 4. AMLA CTR XML
            String amlaBlob = "amla/ctr-TXN-SAMPLE-500K.xml";
            if (!azuriteService.exists(amlaBlob)) {
                String xml = amlaCtrGenerator.generateCtrXml(
                        "TXN-SAMPLE-500K", "ACC-1001", "ACC-2002",
                        new BigDecimal("750000.00"), "PHP", Instant.now()
                );
                azuriteService.uploadArtifact(amlaBlob, xml.getBytes(StandardCharsets.UTF_8), "application/xml");
            }

            log.info("Azurite compliance report seeding completed successfully.");
        } catch (Exception e) {
            log.warn("Could not seed initial Azurite reports: {}", e.getMessage());
        }
    }

    public byte[] generateArtifactOnDemand(String blobName) {
        String lower = blobName.toLowerCase();
        LocalDate now = LocalDate.now();

        if (lower.contains("bir") || lower.contains("2306")) {
            byte[] bytes = birGenerator.generateBir2306Certificate(
                    String.valueOf(now.getYear()), now.minusDays(30), now,
                    "000-111-222-000", "Universal Retail Bank",
                    "999-888-777-000", "Aggregate Retail Depositors",
                    new BigDecimal("50000.00"), new BigDecimal("10000.00")
            );
            azuriteService.uploadArtifact(blobName, bytes, "application/pdf");
            return bytes;
        }

        if (lower.contains("gl") || lower.contains("trial") || lower.contains("reconciliation") || lower.endsWith(".xlsx")) {
            List<GlTrialBalanceGenerator.GlEntry> entries = List.of(
                    new GlTrialBalanceGenerator.GlEntry("10100", "Cash on Hand", "ASSET", new BigDecimal("15000000.00"), BigDecimal.ZERO),
                    new GlTrialBalanceGenerator.GlEntry("10200", "Due from BSP", "ASSET", new BigDecimal("25000000.00"), BigDecimal.ZERO),
                    new GlTrialBalanceGenerator.GlEntry("20100", "Demand Deposits", "LIABILITY", BigDecimal.ZERO, new BigDecimal("40000000.00")),
                    new GlTrialBalanceGenerator.GlEntry("40100", "Fee Income", "REVENUE", BigDecimal.ZERO, new BigDecimal("500000.00")),
                    new GlTrialBalanceGenerator.GlEntry("50100", "Interest Expense", "EXPENSE", new BigDecimal("500000.00"), BigDecimal.ZERO)
            );
            if (lower.endsWith(".xlsx")) {
                byte[] bytes = glGenerator.generateExcel(now, entries);
                azuriteService.uploadArtifact(blobName, bytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                return bytes;
            } else {
                byte[] bytes = glGenerator.generatePdf(now, entries);
                azuriteService.uploadArtifact(blobName, bytes, "application/pdf");
                return bytes;
            }
        }

        if (lower.contains("amla") || lower.contains("ctr")) {
            if (lower.endsWith(".xml")) {
                String xml = amlaCtrGenerator.generateCtrXml(
                        "TXN-" + System.currentTimeMillis(), "ACC-1001", "ACC-2002",
                        new BigDecimal("750000.00"), "PHP", Instant.now()
                );
                byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
                azuriteService.uploadArtifact(blobName, bytes, "application/xml");
                return bytes;
            }
        }

        // Statement fallback
        List<Map<String, Object>> txs = List.of(
                Map.of("date", now.minusDays(5).toString(), "description", "Sample Transaction", "type", "CREDIT", "amount", "1000.00")
        );
        byte[] bytes = statementGenerator.generateStatement(
                "ACC-1001", "1001-2200-3344", new BigDecimal("8500000.00"),
                now.minusDays(30), now, txs
        );
        azuriteService.uploadArtifact(blobName, bytes, "application/pdf");
        return bytes;
    }
}
