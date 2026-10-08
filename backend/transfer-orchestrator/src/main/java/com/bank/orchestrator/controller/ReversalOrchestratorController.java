package com.bank.orchestrator.controller;

import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
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

    @PostMapping("/request")
    public ResponseEntity<Map<?, ?>> requestReversal(@RequestBody Map<String, Object> request) {
        String origTx = String.valueOf(request.getOrDefault("originalTransactionId", request.get("originalTxId")));
        String reason = String.valueOf(request.getOrDefault("reason", request.getOrDefault("disputeReason", "DISPUTE")));
        String maker = String.valueOf(request.getOrDefault("makerId", "MAKER01"));
        String ofsMsg = OfsMessageUtil.buildReversalRequestMessage(origTx, reason, maker);

        String response = cbsWebClient.post()
                .uri("/api/v1/cbs/reversals/request")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .bodyValue(ofsMsg)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<?, ?>> approveReversal(@RequestBody Map<String, Object> request) {
        String ticketId = String.valueOf(request.getOrDefault("reversalRequestId", request.get("ticketId")));
        String checker = String.valueOf(request.getOrDefault("checkerId", "MGR02"));
        String reason = String.valueOf(request.getOrDefault("checkerNotes", "Approved"));
        String ofsMsg = OfsMessageUtil.buildReversalApprovalMessage(ticketId, checker, reason);

        String response = cbsWebClient.post()
                .uri("/api/v1/cbs/reversals/approve")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .bodyValue(ofsMsg)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<?, ?>> rejectReversal(@RequestBody Map<String, Object> request) {
        String ticketId = String.valueOf(request.getOrDefault("reversalRequestId", request.get("ticketId")));
        String checker = String.valueOf(request.getOrDefault("checkerId", "MGR02"));
        String reason = String.valueOf(request.getOrDefault("rejectionReason", request.getOrDefault("checkerNotes", "Rejected")));
        String ofsMsg = OfsMessageUtil.buildReversalRejectionMessage(ticketId, checker, reason);

        String response = cbsWebClient.post()
                .uri("/api/v1/cbs/reversals/reject")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .bodyValue(ofsMsg)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
    }

    @PostMapping({"/direct", "/compensate"})
    public ResponseEntity<Map<?, ?>> directReversal(@RequestBody Map<String, Object> request) {
        String origTx = String.valueOf(request.getOrDefault("originalTransactionId", request.getOrDefault("originalFtNo", request.get("originalTxId"))));
        String reason = String.valueOf(request.getOrDefault("reason", request.getOrDefault("reversalReason", "SAGA_COMPENSATION")));
        String maker = String.valueOf(request.getOrDefault("makerId", "SAGA_COORDINATOR"));
        String checker = String.valueOf(request.getOrDefault("checkerId", "SYSTEM_SAGA"));
        String ofsMsg = String.format("FUNDS.TRANSFER,REVERSAL/I/PROCESS//%s,%s/123456,ORIGINAL.FT.NO=%s,REASON=%s,CHECKER.ID=%s,MAKER.ID=%s",
                origTx, maker, origTx, reason, checker, maker);

        String response = cbsWebClient.post()
                .uri("/api/v1/cbs/reversal")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .bodyValue(ofsMsg)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(OfsMessageUtil.parseOfsFields(response));
    }
}
