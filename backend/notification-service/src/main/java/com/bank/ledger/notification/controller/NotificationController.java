package com.bank.ledger.notification.controller;

import com.bank.ledger.contracts.dto.TransactionNotificationEvent;
import com.bank.ledger.notification.consumer.TransactionEventConsumer;
import com.bank.ledger.notification.entity.NotificationEntity;
import com.bank.ledger.notification.repository.NotificationRepository;
import com.bank.ledger.notification.service.EmailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class NotificationController {

    private final EmailNotificationService emailService;
    private final TransactionEventConsumer transactionEventConsumer;
    private final NotificationRepository notificationRepository;

    @PostMapping("/simulate-transfer")
    public ResponseEntity<Map<String, Object>> simulateTransferNotification(
            @RequestBody(required = false) TransactionNotificationEvent customEvent) {

        TransactionNotificationEvent event = customEvent;
        if (event == null || event.getTransferId() == null) {
            event = TransactionNotificationEvent.builder()
                    .transferId("TRX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                    .sourceAccount("ACC-1002938471")
                    .destinationAccount("ACC-2009847192")
                    .userId("USR-882190")
                    .recipientEmail("customer@corebank.ph")
                    .amount(new BigDecimal("50000.0000"))
                    .currency("PHP")
                    .beforeBalance(new BigDecimal("25000000.0000"))
                    .afterBalance(new BigDecimal("24950000.0000"))
                    .status("COMMITTED")
                    .eventType("TRANSFER_EXECUTED")
                    .timestamp(Instant.now())
                    .description("Retail Fund Transfer via Online Portal")
                    .build();
        }

        log.info("Simulating transfer notification for: {}", event.getTransferId());
        transactionEventConsumer.consumeTransactionEvent(event);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "DISPATCHED");
        response.put("transferId", event.getTransferId());
        response.put("amount", event.getAmount());
        response.put("recipientEmail", event.getRecipientEmail());
        response.put("message", "Simulated transaction consumed; email receipt dispatched & SSE toast pushed.");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/simulate-maker-checker")
    public ResponseEntity<Map<String, Object>> simulateMakerCheckerNotification() {
        TransactionNotificationEvent event = TransactionNotificationEvent.builder()
                .transferId("TRX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .sourceAccount("ACC-1002938471")
                .destinationAccount("ACC-9988776655")
                .userId("USR-882190")
                .makerUserId("USR-882190")
                .recipientEmail("compliance-officer@corebank.ph")
                .amount(new BigDecimal("15000000.0000"))
                .currency("PHP")
                .beforeBalance(new BigDecimal("35000000.0000"))
                .afterBalance(new BigDecimal("20000000.0000"))
                .status("PENDING_APPROVAL")
                .eventType("TRANSFER_PENDING_APPROVAL")
                .requiresMakerChecker(true)
                .timestamp(Instant.now())
                .description("High-Value Corporate Settlement (> PHP 10M)")
                .build();

        log.info("Simulating high-value hold alert for: {}", event.getTransferId());
        transactionEventConsumer.consumeTransactionEvent(event);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ALERT_BROADCAST");
        response.put("transferId", event.getTransferId());
        response.put("amount", event.getAmount());
        response.put("message", "High-value transfer alert broadcast to /topic/teller-alerts and compliance email dispatched.");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<List<NotificationEntity>> getNotificationHistory(
            @RequestParam(value = "userId", required = false) String userId) {
        if (notificationRepository == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        if (userId != null && !userId.isBlank()) {
            return ResponseEntity.ok(notificationRepository.findByUserIdOrderBySentAtDesc(userId));
        }
        return ResponseEntity.ok(notificationRepository.findTop50ByOrderBySentAtDesc());
    }

    @GetMapping("/spool-status")
    public ResponseEntity<Map<String, Object>> getSpoolStatus() {
        return ResponseEntity.ok(emailService.getSpoolStatus());
    }

    @PostMapping("/flush-spool")
    public ResponseEntity<Map<String, Object>> flushSpool() {
        int count = emailService.flushRetrySpool();
        return ResponseEntity.ok(Map.of("flushed_count", count, "status", "SUCCESS"));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("service", "notification-service", "status", "UP", "port", "8083"));
    }
}
