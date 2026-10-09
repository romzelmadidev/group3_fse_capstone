package com.bank.orchestrator.controller;

import com.bank.ledger.contracts.dto.ReversalTicketDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reversals")
public class ReversalOrchestratorController {

    private final WebClient cbsWebClient;

    public ReversalOrchestratorController(
            WebClient.Builder webClientBuilder,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl) {
        this.cbsWebClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
    }

    @GetMapping
    public ResponseEntity<List<ReversalTicketDto>> getReversalRequests(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        try {
            String ofsResp = cbsWebClient.get()
                    .uri(uriBuilder -> {
                        var b = uriBuilder.path("/api/v1/cbs/reversals")
                                .queryParam("page", safePage)
                                .queryParam("size", safeSize);
                        if (status != null && !status.isBlank()) {
                            b.queryParam("status", status.trim().toUpperCase());
                        }
                        return b.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            List<ReversalTicketDto> tickets = OfsMessageUtil.parseReversalListResponse(ofsResp);
            return ResponseEntity.ok(tickets != null ? tickets : Collections.emptyList());
        } catch (Exception e) {
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    @PostMapping("/request")
    public ResponseEntity<Map<?, ?>> requestReversal(@RequestBody Map<String, Object> request) {
        String origTx = String.valueOf(request.getOrDefault("originalTransactionId", request.get("originalTxId")));
        String reason = String.valueOf(request.getOrDefault("reason", request.getOrDefault("disputeReason", "DISPUTE")));
        String maker = String.valueOf(request.getOrDefault("makerId", "MAKER01"));
        String ofsMsg = OfsMessageUtil.buildReversalRequestMessage(origTx, reason, maker);

        try {
            String response = cbsWebClient.post()
                    .uri("/api/v1/cbs/reversals/request")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .bodyValue(ofsMsg)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            String respBody = ex.getResponseBodyAsString();
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(respBody);
            String errMsg = fields.getOrDefault("MESSAGE", fields.getOrDefault("ERROR", ex.getMessage()));
            throw new org.springframework.web.server.ResponseStatusException(ex.getStatusCode(), errMsg);
        }
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<?, ?>> approveReversal(@RequestBody Map<String, Object> request) {
        String ticketId = String.valueOf(request.getOrDefault("reversalRequestId", request.get("ticketId")));
        String checker = String.valueOf(request.getOrDefault("checkerId", "MGR02"));
        String reason = String.valueOf(request.getOrDefault("checkerNotes", "Approved"));
        String ofsMsg = OfsMessageUtil.buildReversalApprovalMessage(ticketId, checker, reason);

        try {
            String response = cbsWebClient.post()
                    .uri("/api/v1/cbs/reversals/approve")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .bodyValue(ofsMsg)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            String respBody = ex.getResponseBodyAsString();
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(respBody);
            String errMsg = fields.getOrDefault("MESSAGE", fields.getOrDefault("ERROR", ex.getMessage()));
            throw new org.springframework.web.server.ResponseStatusException(ex.getStatusCode(), errMsg);
        }
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<?, ?>> rejectReversal(@RequestBody Map<String, Object> request) {
        String ticketId = String.valueOf(request.getOrDefault("reversalRequestId", request.get("ticketId")));
        String checker = String.valueOf(request.getOrDefault("checkerId", "MGR02"));
        String reason = String.valueOf(request.getOrDefault("rejectionReason", request.getOrDefault("checkerNotes", "Rejected")));
        String ofsMsg = OfsMessageUtil.buildReversalRejectionMessage(ticketId, checker, reason);

        try {
            String response = cbsWebClient.post()
                    .uri("/api/v1/cbs/reversals/reject")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .bodyValue(ofsMsg)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            String respBody = ex.getResponseBodyAsString();
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(respBody);
            String errMsg = fields.getOrDefault("MESSAGE", fields.getOrDefault("ERROR", ex.getMessage()));
            throw new org.springframework.web.server.ResponseStatusException(ex.getStatusCode(), errMsg);
        }
    }

    @PostMapping("/direct")
    public ResponseEntity<Map<?, ?>> directReversal(@RequestBody Map<String, Object> request) {
        String origTx = String.valueOf(request.getOrDefault("originalTransactionId", request.getOrDefault("originalFtNo", request.get("originalTxId"))));
        String reason = String.valueOf(request.getOrDefault("reason", request.getOrDefault("reversalReason", "SAGA_COMPENSATION")));
        String maker = String.valueOf(request.getOrDefault("makerId", "SAGA_COORDINATOR"));
        String checker = String.valueOf(request.getOrDefault("checkerId", "SYSTEM_SAGA"));
        String ofsMsg = String.format("FUNDS.TRANSFER,REVERSAL/I/PROCESS//%s,%s/123456,ORIGINAL.FT.NO=%s,REASON=%s,CHECKER.ID=%s,MAKER.ID=%s",
                origTx, maker, origTx, reason, checker, maker);

        try {
            String response = cbsWebClient.post()
                    .uri("/api/v1/cbs/reversal")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .bodyValue(ofsMsg)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(3000))
                    .block();
            return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            String respBody = ex.getResponseBodyAsString();
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(respBody);
            String errMsg = fields.getOrDefault("MESSAGE", fields.getOrDefault("ERROR", ex.getMessage()));
            throw new org.springframework.web.server.ResponseStatusException(ex.getStatusCode(), errMsg);
        }
    }
}
