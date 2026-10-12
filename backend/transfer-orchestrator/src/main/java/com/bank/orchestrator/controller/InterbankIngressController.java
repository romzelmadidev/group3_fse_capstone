package com.bank.orchestrator.controller;

import com.bank.orchestrator.dto.InterbankWireRequest;
import com.bank.orchestrator.dto.InterbankWireResponse;
import com.bank.orchestrator.dto.TransferInitiationRequest;
import com.bank.orchestrator.service.CbsClientService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Handles inbound inter-bank wires from Partner Bank (Group B)
 * and hosts the local Partner Bank Test Simulator.
 * Complies with partner group inter-bank specification.
 */
@RestController
@RequestMapping("/api/v1")
public class InterbankIngressController {

    private static final Logger log = LoggerFactory.getLogger(InterbankIngressController.class);
    private static final Random RANDOM = new Random();

    private final CbsClientService cbsService;
    private final String expectedApiKey;
    private final StringRedisTemplate redisTemplate;

    public InterbankIngressController(
            CbsClientService cbsService,
            String expectedApiKey) {
        this(cbsService, expectedApiKey, (StringRedisTemplate) null);
    }

    @Autowired
    public InterbankIngressController(
            CbsClientService cbsService,
            @Value("${services.partner.api-key:AURABANK-SECURE-KEY-2026}") String expectedApiKey,
            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.cbsService = cbsService;
        this.expectedApiKey = expectedApiKey;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Inward Inter-Bank Wire Endpoint (CooBS -> AuraBank).
     * Leg 3 on AuraBank: Debits ACC-NOSTRO-BANK-B (CooBS Nostro buffer) and credits the local beneficiary.
     */
    @PostMapping({"/interbank/transfers/inward", "/interbank/transfers"})
    public ResponseEntity<InterbankWireResponse> handleInwardWire(
            @RequestHeader(value = "X-Interbank-API-Key", required = false) String apiKey,
            @Valid @RequestBody InterbankWireRequest request) {

        String msgId = request.getEffectiveMessageId() != null
                ? request.getEffectiveMessageId()
                : UUID.randomUUID().toString();

        String beneficiaryAcc = request.getBeneficiaryAccountNumber();
        String senderAcc = request.getSenderAccountNumber();
        String senderName = request.getSenderName() != null ? request.getSenderName() : "Partner Customer";
        String beneficiaryName = request.getBeneficiaryName() != null ? request.getBeneficiaryName() : "Account Holder";
        BigDecimal amount = request.getAmountAsBigDecimal();
        String srcBank = request.getEffectiveSourceBank() != null ? request.getEffectiveSourceBank() : "COOBS";
        String destBank = request.destinationBankCode() != null ? request.destinationBankCode() : "AURABANK";

        log.info("Received Inward Inter-Bank Wire: messageId={}, fromBank={}, sender={}, toBank={}, beneficiary={}, amount={}",
                msgId, srcBank, senderAcc, destBank, beneficiaryAcc, amount);

        // 1. Verify Partner API Key if configured
        if (expectedApiKey != null && !expectedApiKey.isBlank() && apiKey != null && !expectedApiKey.equals(apiKey)) {
            log.warn("Unauthorized inter-bank wire rejected: invalid API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(InterbankWireResponse.rejected(msgId, "UNAUTHORIZED", "Invalid X-Interbank-API-Key"));
        }

        // 2. Validate Beneficiary Account Exists in AuraBank
        if (beneficiaryAcc == null || beneficiaryAcc.isBlank()) {
            log.warn("Inward wire rejected: beneficiary account is missing");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(InterbankWireResponse.rejected(msgId, "ACCOUNT_NOT_FOUND", "No account with that number."));
        }

        Map<String, String> balance = cbsService.getAccountBalance(beneficiaryAcc);
        if (balance == null || balance.isEmpty()
                || ("0.00".equals(balance.get("availableBalance")) && !balance.containsKey("accountNumber") && !balance.containsKey("accountId"))) {
            log.warn("Inward wire failed: Beneficiary account {} not found in AuraBank", beneficiaryAcc);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(InterbankWireResponse.rejected(msgId, "ACCOUNT_NOT_FOUND", "No account with that number."));
        }

        // 3. Leg 3 Core Mutation: Debit ACC-NOSTRO-BANK-B -> Credit Beneficiary Account
        String dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String receiverRef = "IB-" + dateStr + "-" + String.format("%06d", RANDOM.nextInt(900000) + 100000);

        try {
            TransferInitiationRequest inwardReq = new TransferInitiationRequest(
                    receiverRef,
                    "ACC-NOSTRO-BANK-B",
                    beneficiaryAcc,
                    amount,
                    request.currency() != null ? request.currency() : "PHP",
                    request.purpose() != null ? request.purpose() : ("Inward Interbank Wire from " + srcBank),
                    "INTERBANK_GATEWAY",
                    request.senderReference() != null ? request.senderReference() : msgId,
                    null,
                    false,
                    destBank,
                    senderName,
                    null
            );
            cbsService.postToCbs(inwardReq, receiverRef, false);
            log.info("Inward wire committed: {} credited with amount {}", beneficiaryAcc, amount);

            if (redisTemplate != null) {
                try {
                    redisTemplate.delete("account:balance:" + beneficiaryAcc);
                    redisTemplate.delete("account:balance:ACC-NOSTRO-BANK-B");
                } catch (Exception ex) {
                    log.warn("Failed to evict Redis cache for inward wire: {}", ex.getMessage());
                }
            }

            String maskedName = InterbankWireResponse.maskName(beneficiaryName);

            return ResponseEntity.ok(InterbankWireResponse.accepted(
                    msgId,
                    receiverRef,
                    maskedName
            ));

        } catch (Exception e) {
            log.error("Failed to post inward wire to CBS: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(InterbankWireResponse.rejected(msgId, "CBS_ERROR", "CBS Posting Error: " + e.getMessage()));
        }
    }

    /**
     * Local Partner Bank Simulator Endpoint.
     * Allows our team to test outbound wires offline with 3 toggleable modes:
     * - Default: Immediate ACCEPTED response with receiverReference & masked beneficiary
     * - TIMEOUT: Delays 5s to trigger our client timeout and verify automated Saga rollback
     * - REJECT: Returns 400 error with REJECTED status to verify automated Saga rollback on rejection
     */
    @PostMapping("/mock-partner/transfers")
    public ResponseEntity<InterbankWireResponse> simulatePartnerBank(
            @RequestParam(value = "simulationMode", required = false) String simulationMode,
            @RequestBody InterbankWireRequest request) {

        String msgId = request.messageId() != null && !request.messageId().isBlank()
                ? request.messageId()
                : UUID.randomUUID().toString();

        log.info("Local Partner Bank Simulator received wire: mode={}, messageId={}, beneficiary={}, amount={}",
                simulationMode, msgId, request.getBeneficiaryAccountNumber(), request.amount());

        if ("TIMEOUT".equalsIgnoreCase(simulationMode)) {
            try {
                log.info("Partner Simulator simulating 5.5s network lag to exercise Saga rollback...");
                Thread.sleep(5500);
            } catch (InterruptedException ignored) {}
        }

        if ("BLOCKED".equalsIgnoreCase(simulationMode)) {
            log.warn("Partner Simulator simulating rejection (Beneficiary Account Blocked/Dormant)");
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(InterbankWireResponse.rejected(
                            msgId,
                            "ACCOUNT_BLOCKED",
                            "The recipient account is blocked or dormant."
                    ));
        }

        if ("REJECT".equalsIgnoreCase(simulationMode)) {
            log.warn("Partner Simulator simulating rejection (Beneficiary Account Not Found)");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(InterbankWireResponse.rejected(
                            msgId,
                            "ACCOUNT_NOT_FOUND",
                            "No account with that number."
                    ));
        }

        // Happy Path: Success settlement
        String dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String receiverRef = "IB-" + dateStr + "-" + String.format("%06d", RANDOM.nextInt(900000) + 100000);
        String beneficiaryName = request.getBeneficiaryName() != null && !request.getBeneficiaryName().isBlank()
                ? request.getBeneficiaryName()
                : "Juan Dela Cruz";
        String maskedName = InterbankWireResponse.maskName(beneficiaryName);

        return ResponseEntity.ok(InterbankWireResponse.accepted(
                msgId,
                receiverRef,
                maskedName
        ));
    }
}
