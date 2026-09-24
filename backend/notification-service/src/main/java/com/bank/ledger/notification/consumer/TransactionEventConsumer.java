package com.bank.ledger.notification.consumer;

import com.bank.ledger.contracts.dto.TransactionNotificationEvent;
import com.bank.ledger.notification.controller.NotificationStreamController;
import com.bank.ledger.notification.service.EmailNotificationService;
import com.bank.ledger.notification.service.ReceiptGenerator;
import com.bank.ledger.notification.service.TellerAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000000.0000");

    private final EmailNotificationService emailService;
    private final TellerAlertService tellerAlertService;
    private final NotificationStreamController streamController;
    private final ReceiptGenerator receiptGenerator;

    @KafkaListener(
            topics = "${app.kafka.topics.transfers-events:banking.transfers.events}",
            groupId = "${spring.kafka.consumer.group-id:notification-workers}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeTransactionEvent(TransactionNotificationEvent event) {
        if (event == null || event.getTransferId() == null) {
            log.warn("Received empty or malformed transaction event from Kafka");
            return;
        }

        log.info("Consumed Kafka event on banking.transfers.events: transferId={}, status={}, amount={}",
                event.getTransferId(), event.getStatus(), event.getAmount());

        boolean isHighValue = event.getAmount() != null &&
                event.getAmount().compareTo(HIGH_VALUE_THRESHOLD) > 0;

        // 1. Handle Pending Approval / High-Value Holds (SCEN-NOTIF-02)
        if ("PENDING_APPROVAL".equalsIgnoreCase(event.getStatus()) ||
                "TRANSFER_PENDING_APPROVAL".equalsIgnoreCase(event.getEventType()) ||
                (isHighValue && event.isRequiresMakerChecker())) {

            log.info("High-value transfer hold detected for transferId={}. Disagreeing auto-settlement, notifying tellers.",
                    event.getTransferId());
            tellerAlertService.broadcastPendingApprovalAlert(event);
            emailService.sendMakerCheckerAlert(event);
            return;
        }

        // 2. Handle Executed / Committed Settlement (SCEN-NOTIF-01 & SCEN-NOTIF-03)
        if ("COMMITTED".equalsIgnoreCase(event.getStatus()) ||
                "TRANSFER_EXECUTED".equalsIgnoreCase(event.getEventType()) ||
                "SUCCESS".equalsIgnoreCase(event.getStatus())) {

            // Dispatch HTML Email Receipt with Redis deduplication
            boolean sent = emailService.sendTransactionReceipt(event);

            // Push real-time toast to customer browser via SSE
            if (sent) {
                Map<String, Object> toast = new LinkedHashMap<>();
                toast.put("transferId", event.getTransferId());
                toast.put("amount", receiptGenerator.formatCurrencyPhp(event.getAmount()));
                toast.put("afterBalance", receiptGenerator.formatCurrencyPhp(event.getAfterBalance()));
                toast.put("status", "SUCCESS");
                toast.put("message", "Transfer completed successfully. An official receipt has been emailed to you.");
                streamController.pushToast(event.getUserId(), toast);
            }
        } else if ("REJECTED".equalsIgnoreCase(event.getStatus()) || "FAILED".equalsIgnoreCase(event.getStatus())) {
            log.info("Transfer rejected/failed: transferId={}. Sending alert toast.", event.getTransferId());
            Map<String, Object> toast = new LinkedHashMap<>();
            toast.put("transferId", event.getTransferId());
            toast.put("status", "FAILED");
            toast.put("message", "Transaction failed or was rejected. No funds were debited.");
            streamController.pushToast(event.getUserId(), toast);
        }
    }
}
