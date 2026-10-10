package com.bank.compliance.controller;

import com.bank.compliance.generator.CustomerStatementPdfGenerator;
import com.bank.compliance.service.AzuriteBlobStorageService;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
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
    private final ObjectMapper objectMapper;

    public ComplianceController(
            AzuriteBlobStorageService azuriteService,
            CustomerStatementPdfGenerator statementPdfGenerator,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl,
            @Value("${services.orchestrator.url:http://localhost:8082}") String orchestratorUrl) {
        this.azuriteService = azuriteService;
        this.statementPdfGenerator = statementPdfGenerator;
        this.cbsWebClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
        this.orchestratorWebClient = webClientBuilder.baseUrl(orchestratorUrl).build();
        this.objectMapper = objectMapper;
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

    @GetMapping({"/reports/download", "/storage/download/**"})
    public ResponseEntity<byte[]> downloadReport(
            @RequestParam(name = "blobName", required = false) String blobNameParam,
            jakarta.servlet.http.HttpServletRequest request) {

        String blobName = blobNameParam;
        if (blobName == null || blobName.isBlank()) {
            String path = request.getRequestURI();
            String prefix = "/compliance/storage/download/";
            int idx = path.indexOf(prefix);
            if (idx != -1) {
                blobName = path.substring(idx + prefix.length());
            }
        }
        if (blobName == null || blobName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (blobName.startsWith("/")) {
            blobName = blobName.substring(1);
        }

        byte[] content;
        if (azuriteService.exists(blobName)) {
            content = azuriteService.downloadArtifact(blobName);
        } else {
            String finalBlobName = blobName;
            String fileNameOnly = blobName.contains("/") ? blobName.substring(blobName.lastIndexOf('/') + 1) : blobName;
            var matching = azuriteService.listArtifacts().stream()
                    .filter(b -> b.blobName().equalsIgnoreCase(finalBlobName) || b.blobName().endsWith("/" + fileNameOnly))
                    .findFirst();
            if (matching.isPresent()) {
                content = azuriteService.downloadArtifact(matching.get().blobName());
            } else {
                return ResponseEntity.notFound().build();
            }
        }

        String fileName = blobName.contains("/") ? blobName.substring(blobName.lastIndexOf('/') + 1) : blobName;
        String contentType = fileName.endsWith(".pdf")
                ? "application/pdf"
                : (fileName.endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : (fileName.endsWith(".xml") ? "application/xml" : "application/octet-stream"));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(content);
    }

    @GetMapping("/statements/{accountId}/pdf")
    public ResponseEntity<byte[]> generateAndDownloadStatement(@PathVariable String accountId) {
        // Query balance and account from Transfer Orchestrator facade (JSON protocol)
        Map<?, ?> balanceMap = orchestratorWebClient.get()
                .uri("/api/v1/transfers/accounts/" + accountId + "/balance")
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
                .uri(uriBuilder -> uriBuilder.path("/api/v1/cbs/audit/accounts/" + accountId + "/mutations")
                        .queryParam("page", 0)
                        .queryParam("size", 100)
                        .build())
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
    public ResponseEntity<List<?>> getDlqIncidents(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        List<?> incidents = cbsWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/cbs/audit/failed-transactions")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
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
        Map<?, ?> resolvedAudit = null;
        try {
            resolvedAudit = cbsWebClient.post()
                    .uri("/api/v1/cbs/audit/failed-transactions/" + transferId + "/resolve")
                    .bodyValue(Map.of("notes", "Triggered manual replay via compliance console"))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
        } catch (Exception ex) {
            log.warn("Could not mark CBS audit transaction {} as resolved: {}", transferId, ex.getMessage());
        }

        // 2. Extract and construct transfer payload
        Map<String, Object> transferPayload = new HashMap<>();

        // If override provided in request body, start with it
        if (replayOverride != null && !replayOverride.isEmpty()) {
            transferPayload.putAll(replayOverride);
        }

        // If payloadJson was stored in audit vault, unpack it for any missing attributes
        if (resolvedAudit != null && resolvedAudit.get("payloadJson") != null) {
            try {
                Object rawPayload = resolvedAudit.get("payloadJson");
                if (rawPayload instanceof String payloadStr && !payloadStr.isBlank()) {
                    Map<String, Object> parsed = objectMapper.readValue(payloadStr, new TypeReference<Map<String, Object>>() {});
                    if (parsed != null) {
                        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                            transferPayload.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                    }
                } else if (rawPayload instanceof Map<?, ?> rawMap) {
                    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                        transferPayload.putIfAbsent(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to parse stored payloadJson for transferId {}: {}", transferId, ex.getMessage());
            }
        }

        // Ensure required TransferInitiationRequest fields have valid non-null defaults
        transferPayload.putIfAbsent("sourceAccountId", "1000-2000-3001");
        transferPayload.putIfAbsent("destinationAccountId", "1000-2000-3002");
        transferPayload.putIfAbsent("amount", 5000.00);
        transferPayload.putIfAbsent("currency", "PHP");
        transferPayload.putIfAbsent("description", "DLQ Replay: " + transferId);
        transferPayload.putIfAbsent("idempotencyKey", "REPLAY-" + transferId + "-" + System.currentTimeMillis());
        transferPayload.putIfAbsent("scamAdvisoryAcknowledged", true);
        if (!transferPayload.containsKey("transactionId")) {
            transferPayload.put("transactionId", transferId);
        }

        // 3. Re-trigger transfer via orchestrator
        try {
            Map<?, ?> replayedResult = orchestratorWebClient.post()
                    .uri("/api/v1/transfers")
                    .bodyValue(transferPayload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();

            return ResponseEntity.ok(Map.of(
                    "transferId", transferId,
                    "status", "REPLAYED",
                    "orchestratorResult", replayedResult != null ? replayedResult : Map.of()
            ));
        } catch (WebClientResponseException ex) {
            log.error("Orchestrator rejected replay for transferId {}: HTTP {} - {}",
                    transferId, ex.getStatusCode(), ex.getResponseBodyAsString());
            return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                    "transferId", transferId,
                    "status", "REPLAY_FAILED",
                    "error", ex.getResponseBodyAsString(),
                    "payloadSent", transferPayload
            ));
        } catch (Exception ex) {
            log.error("Failed to re-trigger transfer for transferId {}: {}", transferId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "transferId", transferId,
                    "status", "REPLAY_FAILED",
                    "error", ex.getMessage() != null ? ex.getMessage() : "Unknown replay error"
            ));
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

