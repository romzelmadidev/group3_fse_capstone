package com.bank.compliance;

import com.bank.compliance.generator.AmlaCtrGenerator;
import com.bank.compliance.generator.BirTaxCertificateGenerator;
import com.bank.compliance.generator.CustomerStatementPdfGenerator;
import com.bank.compliance.generator.GlTrialBalanceGenerator;
import com.bank.compliance.service.AzuriteBlobStorageService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComplianceServiceTest {

    @Test
    void testCustomerStatementPdfGenerator_ProducesValidPdfBytes() {
        CustomerStatementPdfGenerator generator = new CustomerStatementPdfGenerator();
        List<Map<String, Object>> txs = List.of(
                Map.of("date", "2026-10-01", "description", "Payroll Credit", "type", "CREDIT", "amount", "50000.00"),
                Map.of("date", "2026-10-05", "description", "Utility Bill", "type", "DEBIT", "amount", "2500.00")
        );

        byte[] pdf = generator.generateStatement(
                "ACC-101", "0011-2233-44", new BigDecimal("47500.00"),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7), txs
        );

        assertNotNull(pdf);
        assertTrue(pdf.length > 500, "PDF should have substantive binary content");
        // Check standard PDF file header (%PDF)
        assertEquals('%', (char) pdf[0]);
        assertEquals('P', (char) pdf[1]);
        assertEquals('D', (char) pdf[2]);
        assertEquals('F', (char) pdf[3]);
    }

    @Test
    void testGlTrialBalanceExcelAndPdfGenerators_ProduceValidBytes() {
        GlTrialBalanceGenerator generator = new GlTrialBalanceGenerator();
        List<GlTrialBalanceGenerator.GlEntry> entries = List.of(
                new GlTrialBalanceGenerator.GlEntry("10100", "Cash", "ASSET", new BigDecimal("1000.00"), BigDecimal.ZERO),
                new GlTrialBalanceGenerator.GlEntry("20100", "Deposits", "LIABILITY", BigDecimal.ZERO, new BigDecimal("1000.00"))
        );

        byte[] excel = generator.generateExcel(LocalDate.now(), entries);
        assertNotNull(excel);
        assertTrue(excel.length > 500);

        byte[] pdf = generator.generatePdf(LocalDate.now(), entries);
        assertNotNull(pdf);
        assertTrue(pdf.length > 500);
        assertEquals('%', (char) pdf[0]);
    }

    @Test
    void testBir2306TaxCertificateGenerator_ProducesValidPdf() {
        BirTaxCertificateGenerator generator = new BirTaxCertificateGenerator();
        byte[] pdf = generator.generateBir2306Certificate(
                "2026", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7),
                "000-111-222-000", "Universal Retail Bank",
                "123-456-789-000", "Juan Dela Cruz",
                new BigDecimal("1000.00"), new BigDecimal("200.00")
        );

        assertNotNull(pdf);
        assertTrue(pdf.length > 500);
        assertEquals('%', (char) pdf[0]);
    }

    @Test
    void testAmlaCtrGenerator_ProducesCompliantXml() {
        AmlaCtrGenerator generator = new AmlaCtrGenerator();
        String xml = generator.generateCtrXml(
                "TXN-AMLA-1", "ACC-SENDER", "ACC-BENEFICIARY",
                new BigDecimal("750000.00"), "PHP", Instant.now()
        );

        assertNotNull(xml);
        assertTrue(xml.contains("<CoveredTransactionReport"));
        assertTrue(xml.contains("<Amount currency=\"PHP\">750000.00</Amount>"));
        assertTrue(xml.contains("<Originator>"));
        assertTrue(xml.contains("<Beneficiary>"));
        assertTrue(xml.contains("ComplianceThreshold"));
    }

    @Test
    void testSha256Checksum_Computation() {
        byte[] data = "Hello, Azurite immutable vault!".getBytes();
        String hash = AzuriteBlobStorageService.computeSha256(data);
        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 hash must be 64 hexadecimal characters");
    }
}
