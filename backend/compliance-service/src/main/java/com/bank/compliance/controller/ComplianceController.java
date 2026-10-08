package com.bank.compliance.controller;

import com.bank.compliance.generator.CustomerStatementPdfGenerator;
import com.bank.compliance.service.AzuriteBlobStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    private static final Logger log = LoggerFactory.getLogger(ComplianceController.class);

    private final AzuriteBlobStorageService azuriteService;
    private final CustomerStatementPdfGenerator statementPdfGenerator;
    private final WebClient cbsWebClient;
    private final WebClient orchestratorWebClient;

    public ComplianceController(
            AzuriteBlobStorageService azuriteService,
            CustomerStatementPdfGenerator statementPdfGenerator,
            WebClient.Builder webClientBuilder,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl,
            @Value("${services.orchestrator.url:http://localhost:8082}") String orchestratorUrl) {
        this.azuriteService = azuriteService;
        this.statementPdfGenerator = statementPdfGenerator;
        this.cbsWebClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
        this.orchestratorWebClient = webClientBuilder.baseUrl(orchestratorUrl).build();
    }

    @GetMapping("/reports")
    public ResponseEntity<List<?>> getEodReports(@RequestParam(name = "eodDate", required = false) String eodDate) {
        String dateStr = eodDate != null ? eodDate : LocalDate.now().toString();
        List<?> reports = cbsWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/cbs/audit/eod-reports").queryParam("eodDate", dateStr).build())
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(3))
                .block();
        return ResponseEntity.ok(reports);
    }

    @GetMapping("/reports/download")
    public ResponseEntity<byte[]> downloadReport(@RequestParam("blobName") String blobName) {
        byte[] content = azuriteService.downloadArtifact(blobName);
        String contentType = blobName.endsWith(".pdf")
                ? "application/pdf"
                : (blobName.endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/octet-stream");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + blobName.substring(blobName.lastIndexOf('/') + 1) + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }

    @GetMapping("/statements/{accountId}/pdf")
    public ResponseEntity<byte[]> generateAndDownloadStatement(@PathVariable String accountId) {
        // Query balance and account from CBS
        Map<?, ?> balanceMap = cbsWebClient.get()
                .uri("/api/v1/cbs/accounts/" + accountId + "/balance")
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(3))
                .block();

        String accNum = (balanceMap != null && balanceMap.get("accountNumber") != null)
                ? String.valueOf(balanceMap.get("accountNumber"))
                : accountId;
        BigDecimal curBal = (balanceMap != null && balanceMap.get("currentBalance") != null)
                ? new BigDecimal(String.valueOf(balanceMap.get("currentBalance")))
                : BigDecimal.ZERO;

        List<?> mutations = cbsWebClient.get()
                .uri("/api/v1/cbs/audit/accounts/" + accountId + "/mutations")
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(3))
                .block();

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(30);

        byte[] pdfBytes = statementPdfGenerator.generateStatement(
                accountId, accNum, curBal, start, end,
                (List<Map<String, Object>>) (List<?>) mutations
        );

        String blobName = "statements/" + accountId + "/statement-" + end + ".pdf";
        azuriteService.uploadArtifact(blobName, pdfBytes, "application/pdf");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"statement-" + accNum + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @GetMapping("/dlq/incidents")
    public ResponseEntity<List<?>> getDlqIncidents() {
        List<?> incidents = cbsWebClient.get()
                .uri("/api/v1/cbs/audit/failed-transactions")
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(3))
                .block();
        return ResponseEntity.ok(incidents);
    }

    @PostMapping(path = {"/dlq/replays/{transferId}", "/dlq/replay/{transferId}"})
    public ResponseEntity<Map<String, Object>> replayDlqTransaction(
            @PathVariable String transferId,
            @RequestBody(required = false) Map<String, Object> replayOverride) {
        log.info("Initiating DLQ replay for failed transfer ID: {}", transferId);

        // 1. Resolve incident in CBS audit vault
        cbsWebClient.post()
                .uri("/api/v1/cbs/audit/failed-transactions/" + transferId + "/resolve")
                .bodyValue(Map.of("notes", "Triggered manual replay via compliance console"))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(3))
                .block();

        // 2. Re-trigger transfer via orchestrator
        Map<?, ?> replayedResult = orchestratorWebClient.post()
                .uri("/api/v1/transfers")
                .bodyValue(replayOverride != null ? replayOverride : Map.of("transactionId", transferId))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        return ResponseEntity.ok(Map.of(
                "transferId", transferId,
                "status", "REPLAYED",
                "orchestratorResult", replayedResult != null ? replayedResult : Map.of()
        ));
    }

    @PostMapping("/dlq/simulate")
    public ResponseEntity<?> simulateDlqFailure(@RequestBody(required = false) Map<String, String> body) {
        log.info("Simulating DLQ failure incident: {}", body);
        try {
            Map<?, ?> res = cbsWebClient.post()
                    .uri("/api/v1/cbs/audit/failed-transactions/simulate")
                    .bodyValue(body != null ? body : Map.of())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return ResponseEntity.ok(res != null ? res : Map.of("status", "SIMULATED"));
        } catch (Exception e) {
            log.error("Failed to simulate DLQ incident: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/azurite/blobs")
    public ResponseEntity<List<AzuriteBlobStorageService.BlobItemDto>> listAzuriteBlobs() {
        return ResponseEntity.ok(azuriteService.listArtifacts());
    }

    @PostMapping(value = "/azurite/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AzuriteBlobStorageService.UploadResult> uploadToAzurite(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "compliance") String folder) {
        try {
            String blobName = folder + "/" + (file.getOriginalFilename() != null ? file.getOriginalFilename() : "report.bin");
            String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
            var result = azuriteService.uploadArtifact(blobName, file.getBytes(), contentType);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload to Azurite: " + e.getMessage(), e);
        }
    }
}

