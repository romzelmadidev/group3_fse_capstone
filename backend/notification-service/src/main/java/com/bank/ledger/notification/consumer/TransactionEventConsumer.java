package com.bank.ledger.notification.consumer;

import com.bank.ledger.contracts.dto.TransactionNotificationEvent;
import com.bank.ledger.notification.controller.NotificationStreamController;
import com.bank.ledger.notification.entity.NotificationEntity;
import com.bank.ledger.notification.repository.NotificationRepository;
import com.bank.ledger.notification.service.EmailNotificationService;
import com.bank.ledger.notification.service.ManagerAlertService;
import com.bank.ledger.notification.service.ReceiptGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEventConsumer {

    // BSP MORB & AMLA Transaction Tier Thresholds
    public static final BigDecimal BSP_TIER_1_MAX = new BigDecimal("50000.0000");   // <= 50k: Normal (Teller only)
    public static final BigDecimal BSP_TIER_3_MIN = new BigDecimal("500000.0000");  // >= 500k: AMLA Covered (CTR + Dual Manager)

    private final EmailNotificationService emailService;
    private final ManagerAlertService managerAlertService;
    private final NotificationStreamController streamController;
    private final ReceiptGenerator receiptGenerator;
    private final NotificationRepository notificationRepository;

    @Value("${app.services.account-service-url:http://account-service:8081}")
    private String accountServiceUrl;

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

        BigDecimal amount = event.getAmount() != null ? event.getAmount() : BigDecimal.ZERO;
        boolean exceedsTellerLimit = amount.compareTo(BSP_TIER_1_MAX) > 0;
        boolean isAmlaCovered = amount.compareTo(BSP_TIER_3_MIN) >= 0;

        log.info("Consumed Kafka event on banking.transfers.events: transferId={}, status={}, amount={}, exceedsLimit={}, amlaCovered={}",
                event.getTransferId(), event.getStatus(), amount, exceedsTellerLimit, isAmlaCovered);

        // 1. Handle Pending Approval / Exceed Limit Holds (BSP MORB Maker-Checker & AMLA CTR)
        boolean isPending = "PENDING_APPROVAL".equalsIgnoreCase(event.getStatus()) ||
                "TRANSFER_PENDING_APPROVAL".equalsIgnoreCase(event.getEventType()) ||
                event.isRequiresMakerChecker() ||
                (exceedsTellerLimit && !"COMMITTED".equalsIgnoreCase(event.getStatus()) && !"SUCCESS".equalsIgnoreCase(event.getStatus()));

        if (isPending) {
            if (isAmlaCovered) {
                // Tier 3: High-Value / AMLA Covered (>= PHP 500,000.00)
                // Roles: Maker: Customer | Checker 1: Manager L1 | Approver 2: Senior Manager L2
                log.warn("TIER 3 AMLA HOLD for transferId={}. Amount={} >= 500k. Requires CTR filing + Dual Manager approval.",
                        event.getTransferId(), amount);
                managerAlertService.broadcastTier3AmlaAlert(event);
                emailService.sendAmlaHighValueAlert(event);
            } else {
                // Tier 2: Dual Control Maker-Checker (PHP 50,000.01 – PHP 499,999.99)
                // Roles: Maker: Customer | Checker: Bank Operations Manager (Level 1)
                log.info("TIER 2 DUAL CONTROL HOLD for transferId={}. Amount={} > 50k. Requires Manager review in Manager Console.",
                        event.getTransferId(), amount);
                managerAlertService.broadcastTier2MakerCheckerAlert(event);
                emailService.sendMakerCheckerAlert(event);
            }
            return;
        }

        // 2. Handle Executed / Committed Settlement (SCEN-NOTIF-01 & SCEN-NOTIF-03)
        if ("COMMITTED".equalsIgnoreCase(event.getStatus()) ||
                "TRANSFER_EXECUTED".equalsIgnoreCase(event.getEventType()) ||
                "SUCCESS".equalsIgnoreCase(event.getStatus())) {

            // Dispatch HTML Email Receipt with Redis deduplication
            boolean sent = emailService.sendTransactionReceipt(event);

            // Push real-time toast to customer browser via SSE
            Map<String, Object> toast = new LinkedHashMap<>();
            toast.put("type", "TRANSACTION_ALERT");
            toast.put("transferId", event.getTransferId());
            toast.put("amount", receiptGenerator.formatCurrencyPhp(event.getAmount()));
            toast.put("afterBalance", receiptGenerator.formatCurrencyPhp(event.getAfterBalance()));
            toast.put("counterparty", event.getDestinationAccount() != null ? event.getDestinationAccount() : "Beneficiary");
            toast.put("status", "SUCCESS");
            toast.put("message", "Transfer completed successfully. An official receipt has been emailed to you.");
            streamController.pushToast(event.getUserId(), toast);
            notifyRecipient(event);
        } else if ("REJECTED".equalsIgnoreCase(event.getStatus()) || "FAILED".equalsIgnoreCase(event.getStatus())) {
            log.info("Transfer rejected/failed: transferId={}. Sending alert toast.", event.getTransferId());
            Map<String, Object> toast = new LinkedHashMap<>();
            toast.put("type", "TRANSACTION_ALERT");
            toast.put("transferId", event.getTransferId());
            toast.put("status", "FAILED");
            toast.put("message", "Transaction failed or was rejected. No funds were debited.");
            streamController.pushToast(event.getUserId(), toast);
        }
    }

    /**
     * Tells the account holder on the receiving end that money arrived. The event only carries
     * the sender's user id, so the owner of the destination account is looked up in account-service.
     */
    private void notifyRecipient(TransactionNotificationEvent event) {
        String recipientUserId = resolveAccountOwner(event.getDestinationAccount());
        if (recipientUserId == null || recipientUserId.equals(event.getUserId())) {
            return;
        }
        String amount = receiptGenerator.formatCurrencyPhp(event.getAmount());
        String message = "You received " + amount + " from account " + event.getSourceAccount()
                + ". Ref: " + event.getTransferId();
        String notificationId = "NOTIF-IN-" + event.getTransferId();

        try {
            notificationRepository.save(NotificationEntity.builder()
                    .notificationId(notificationId)
                    .userId(recipientUserId)
                    .type("TRANSACTION_ALERT")
                    .message(message)
                    .sentAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("Could not persist incoming-funds notification for {}: {}", recipientUserId, e.getMessage());
        }

        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("notification_id", notificationId);
        toast.put("type", "TRANSACTION_ALERT");
        toast.put("user_id", recipientUserId);
        toast.put("transferId", event.getTransferId());
        toast.put("amount", amount);
        toast.put("counterparty", event.getSourceAccount());
        toast.put("status", "SUCCESS");
        toast.put("isIncoming", true);
        toast.put("message", message);
        toast.put("timestamp", Instant.now().toString());
        streamController.pushToast(recipientUserId, toast);
    }

    private String resolveAccountOwner(String accountId) {
        if (accountId == null || accountId.isBlank()) return null;
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2000);
            factory.setReadTimeout(2000);
            Map<?, ?> account = new RestTemplate(factory)
                    .getForObject(accountServiceUrl + "/api/v1/accounts/{id}", Map.class, accountId);
            Object owner = account != null ? account.get("user_id") : null;
            return owner != null ? owner.toString() : null;
        } catch (Exception e) {
            log.warn("Could not resolve owner of account {}: {}", accountId, e.getMessage());
            return null;
        }
    }
}
