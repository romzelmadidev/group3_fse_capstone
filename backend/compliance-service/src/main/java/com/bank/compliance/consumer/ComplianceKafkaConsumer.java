package com.bank.compliance.consumer;

import com.bank.compliance.generator.AmlaCtrGenerator;
import com.bank.compliance.generator.BirTaxCertificateGenerator;
import com.bank.compliance.generator.GlTrialBalanceGenerator;
import com.bank.compliance.service.AzuriteBlobStorageService;
import com.bank.ledger.contracts.dto.events.BalanceSnapshotFrozenEvent;
import com.bank.ledger.contracts.dto.events.EodCompletedEvent;
import com.bank.ledger.contracts.dto.events.TransferExecutedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ComplianceKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ComplianceKafkaConsumer.class);
    private static final BigDecimal AMLA_CTR_THRESHOLD = new BigDecimal("500000.00");

    private final AzuriteBlobStorageService azuriteService;
    private final AmlaCtrGenerator amlaCtrGenerator;
    private final GlTrialBalanceGenerator glGenerator;
    private final BirTaxCertificateGenerator birGenerator;
    private final WebClient cbsWebClient;
    private final ObjectMapper objectMapper;

    public ComplianceKafkaConsumer(
            AzuriteBlobStorageService azuriteService,
            AmlaCtrGenerator amlaCtrGenerator,
            GlTrialBalanceGenerator glGenerator,
            BirTaxCertificateGenerator birGenerator,
            WebClient.Builder webClientBuilder,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl,
            ObjectMapper objectMapper) {
        this.azuriteService = azuriteService;
        this.amlaCtrGenerator = amlaCtrGenerator;
        this.glGenerator = glGenerator;
        this.birGenerator = birGenerator;
        this.cbsWebClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "banking.transfers.events", groupId = "compliance-service-workers")
    public void consumeTransferEvents(String messagePayload) {
        try {
            JsonNode node = objectMapper.readTree(messagePayload);
            if (node.has("amount") && node.has("sourceAccountId") && node.has("destinationAccountId")) {
                TransferExecutedEvent event = objectMapper.treeToValue(node, TransferExecutedEvent.class);
                if (event.getAmount() != null && event.getAmount().compareTo(AMLA_CTR_THRESHOLD) >= 0) {
                    log.info("Transaction {} exceeds AMLA CTR threshold ({} >= 500,000). Generating filing package.",
                            event.getTransactionId(), event.getAmount());
                    handleAmlaCtrFiling(event);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process transfer event in compliance consumer: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "banking.batch.events", groupId = "compliance-service-workers")
    public void consumeBatchEvents(String messagePayload) {
        try {
            JsonNode node = objectMapper.readTree(messagePayload);
            if (node.has("snapshotsCount")) {
                BalanceSnapshotFrozenEvent event = objectMapper.treeToValue(node, BalanceSnapshotFrozenEvent.class);
                log.info("Batch snapshot frozen event received for date {}. Generating EOD artifacts.", event.getBusinessDate());
                handleEodArtifactGeneration(event.getBusinessDate(), event.getSnapshotsCount());
            }
        } catch (Exception e) {
            log.error("Failed to process batch event in compliance consumer: {}", e.getMessage(), e);
        }
    }

    private void handleAmlaCtrFiling(TransferExecutedEvent event) {
        String xml = amlaCtrGenerator.generateCtrXml(
                event.getTransactionId(),
                event.getSourceAccountId(),
                event.getDestinationAccountId(),
                event.getAmount(),
                event.getCurrency(),
                event.getExecutedAtUtc()
        );

        byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
        String blobName = "amla/ctr-" + event.getTransactionId() + ".xml";

        AzuriteBlobStorageService.UploadResult upload = azuriteService.uploadArtifact(blobName, xmlBytes, "application/xml");
        log.info("Uploaded AMLA CTR to Azurite: uri={}, sha256={}", upload.storageUri(), upload.sha256Checksum());

        // Register compliance filing in CBS
        Map<String, Object> filing = Map.of(
                "filingId", UUID.randomUUID().toString(),
                "filingType", "CTR_500K",
                "businessDate", LocalDate.now().toString(),
                "transactionId", event.getTransactionId(),
                "payloadXml", xml,
                "amlcRef", "AMLC-" + event.getTransactionId().substring(0, 8),
                "filedAt", Instant.now().toString()
        );

        try {
            cbsWebClient.post()
                    .uri("/api/v1/cbs/audit/compliance-filings")
                    .bodyValue(filing)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            log.info("Registered AMLA CTR filing in CBS audit vault for txId={}", event.getTransactionId());
        } catch (Exception e) {
            log.error("Failed to register compliance filing in CBS: {}", e.getMessage());
        }
    }

    private void handleEodArtifactGeneration(String businessDateStr, int accountCount) {
        LocalDate bDate = LocalDate.parse(businessDateStr);

        // 1. Generate & Upload GL Trial Balance Excel
        List<GlTrialBalanceGenerator.GlEntry> entries = List.of(
                new GlTrialBalanceGenerator.GlEntry("10100", "Cash on Hand", "ASSET", new BigDecimal("15000000.00"), BigDecimal.ZERO),
                new GlTrialBalanceGenerator.GlEntry("10200", "Due from BSP", "ASSET", new BigDecimal("25000000.00"), BigDecimal.ZERO),
                new GlTrialBalanceGenerator.GlEntry("20100", "Demand Deposits", "LIABILITY", BigDecimal.ZERO, new BigDecimal("40000000.00")),
                new GlTrialBalanceGenerator.GlEntry("40100", "Fee Income", "REVENUE", BigDecimal.ZERO, new BigDecimal("500000.00")),
                new GlTrialBalanceGenerator.GlEntry("50100", "Interest Expense", "EXPENSE", new BigDecimal("500000.00"), BigDecimal.ZERO)
        );

        byte[] excelBytes = glGenerator.generateExcel(bDate, entries);
        String excelBlob = "eod/gl-trial-balance-" + bDate + ".xlsx";
        AzuriteBlobStorageService.UploadResult excelUpload = azuriteService.uploadArtifact(
                excelBlob, excelBytes, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        registerReportMetadata(bDate, "GL_TRIAL_BALANCE_EXCEL", "gl-trial-balance-" + bDate + ".xlsx",
                excelUpload.storageUri(), excelUpload.sha256Checksum(), excelBytes.length, (long) entries.size());

        // 2. Generate & Upload BIR Form 2306 Withholding Certificate PDF
        byte[] birBytes = birGenerator.generateBir2306Certificate(
                String.valueOf(bDate.getYear()),
                bDate.withDayOfMonth(1),
                bDate,
                "000-111-222-000", "Universal Retail Bank",
                "999-888-777-000", "Aggregate Retail Depositors",
                new BigDecimal("50000.00"), new BigDecimal("10000.00")
        );
        String birBlob = "eod/bir-2306-" + bDate + ".pdf";
        AzuriteBlobStorageService.UploadResult birUpload = azuriteService.uploadArtifact(birBlob, birBytes, "application/pdf");

        registerReportMetadata(bDate, "BIR_FORM_2306_WITHHOLDING_PDF", "bir-2306-" + bDate + ".pdf",
                birUpload.storageUri(), birUpload.sha256Checksum(), birBytes.length, (long) accountCount);
    }

    private void registerReportMetadata(LocalDate bDate, String reportType, String fileName,
                                         String storageUri, String checksum, long sizeBytes, long recordCount) {
        Map<String, Object> meta = Map.of(
                "reportId", UUID.randomUUID().toString(),
                "reportType", reportType,
                "businessDate", bDate.toString(),
                "fileName", fileName,
                "storageUri", storageUri,
                "sha256Checksum", checksum,
                "fileSizeBytes", sizeBytes,
                "recordCount", recordCount,
                "retentionExpiryDate", Instant.now().plus(Duration.ofDays(365 * 10)).toString()
        );

        try {
            cbsWebClient.post()
                    .uri("/api/v1/cbs/audit/eod-reports")
                    .bodyValue(meta)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            log.info("Registered EOD report {} in CBS audit vault", reportType);
        } catch (Exception e) {
            log.error("Failed to register EOD report metadata in CBS: {}", e.getMessage());
        }
    }
}
