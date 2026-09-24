package com.bank.ledger.notification.service;

import com.bank.ledger.contracts.dto.TransactionNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TellerAlertService {

    private final SimpMessagingTemplate messagingTemplate;
    private final ReceiptGenerator receiptGenerator;

    public void broadcastPendingApprovalAlert(TransactionNotificationEvent event) {
        Map<String, Object> alertPayload = new LinkedHashMap<>();
        alertPayload.put("alert_type", "MAKER_CHECKER_REQUIRED");
        alertPayload.put("transfer_id", event.getTransferId());
        alertPayload.put("amount", event.getAmount());
        alertPayload.put("formatted_amount", receiptGenerator.formatCurrencyPhp(event.getAmount()));
        alertPayload.put("maker_user_id", event.getMakerUserId() != null ? event.getMakerUserId() : "SYSTEM");
        alertPayload.put("source_account", event.getSourceAccount());
        alertPayload.put("destination_account", event.getDestinationAccount());
        alertPayload.put("timestamp", Instant.now().toString());

        log.info("Broadcasting Maker-Checker alert to /topic/teller-alerts for transfer: {}", event.getTransferId());
        messagingTemplate.convertAndSend("/topic/teller-alerts", alertPayload);
    }
}
