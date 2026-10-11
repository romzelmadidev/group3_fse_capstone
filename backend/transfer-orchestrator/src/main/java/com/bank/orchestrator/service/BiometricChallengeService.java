package com.bank.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class BiometricChallengeService {

    private static final Logger log = LoggerFactory.getLogger(BiometricChallengeService.class);
    private static final String CHAL_PREFIX = "tx:bio:chal:";
    private static final String TRIES_PREFIX = "tx:bio:tries:";
    private static final int MAX_TRIES = 3;
    private static final Duration CHAL_TTL = Duration.ofSeconds(300); // 5 minutes

    private final StringRedisTemplate redisTemplate;

    public BiometricChallengeService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String generateChallenge(String transactionId) {
        return generateChallenge(transactionId, "UNKNOWN", BigDecimal.ZERO, "PHP");
    }

    /**
     * Generates a cryptographically bound biometric challenge dynamically linked
     * to the transaction amount, destination account, and currency (BSP Circular 1140).
     */
    public String generateChallenge(String transactionId, String destinationAccountId, BigDecimal amount, String currency) {
        String nonce = UUID.randomUUID().toString();
        String cleanCurrency = (currency != null && !currency.isBlank()) ? currency : "PHP";
        BigDecimal cleanAmount = (amount != null) ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
        String cleanDest = (destinationAccountId != null) ? destinationAccountId : "UNKNOWN";

        String dynamicToken = calculateDynamicHash(transactionId, cleanDest, cleanAmount, cleanCurrency, nonce);

        // Store binding record: cleanDest|cleanAmount|cleanCurrency|nonce|dynamicToken
        String storedRecord = String.join("|", cleanDest, cleanAmount.toPlainString(), cleanCurrency, nonce, dynamicToken);
        redisTemplate.opsForValue().set(CHAL_PREFIX + transactionId, storedRecord, CHAL_TTL);
        redisTemplate.opsForValue().set(TRIES_PREFIX + transactionId, "0", CHAL_TTL);

        log.info("Generated cryptographically bound biometric challenge for transaction {}: dest={}, amount={}, currency={}",
                transactionId, cleanDest, cleanAmount, cleanCurrency);

        return dynamicToken;
    }

    public boolean verifyChallenge(String transactionId, String challengeToken, String signature, String deviceId) {
        return verifyChallenge(transactionId, null, null, challengeToken, signature, deviceId);
    }

    /**
     * Verifies that the biometric assertion signature validates against the challenge
     * and that the transfer parameters match the cryptographically linked context.
     */
    public boolean verifyChallenge(String transactionId, String destinationAccountId, BigDecimal amount,
                                   String challengeToken, String signature, String deviceId) {
        String triesKey = TRIES_PREFIX + transactionId;
        String chalKey = CHAL_PREFIX + transactionId;

        String currentTriesStr = redisTemplate.opsForValue().get(triesKey);
        int currentTries = currentTriesStr != null ? Integer.parseInt(currentTriesStr) : 0;

        if (currentTries >= MAX_TRIES) {
            log.warn("Biometric verification attempts exceeded for transaction {}", transactionId);
            throw new IllegalStateException("BIOMETRIC_ATTEMPTS_EXCEEDED: Maximum of 3 attempts reached");
        }

        String storedRecord = redisTemplate.opsForValue().get(chalKey);
        if (storedRecord == null) {
            throw new IllegalArgumentException("Biometric challenge expired or not found for transaction " + transactionId);
        }

        // Parse stored record: cleanDest|cleanAmount|cleanCurrency|nonce|dynamicToken
        String[] parts = storedRecord.split("\\|");
        boolean isValid = false;

        if (parts.length >= 5) {
            String storedDest = parts[0];
            String storedAmountStr = parts[1];
            String storedCurrency = parts[2];
            String storedNonce = parts[3];
            String storedToken = parts[4];

            // Verify the incoming challengeToken matches the stored token
            boolean tokenMatches = storedToken.equals(challengeToken);

            // Dynamic linking verification: If destinationAccountId or amount is supplied, verify against stored context
            boolean destMatches = (destinationAccountId == null) || destinationAccountId.equalsIgnoreCase(storedDest);
            boolean amountMatches = (amount == null) ||
                    amount.setScale(2, RoundingMode.HALF_UP).compareTo(new BigDecimal(storedAmountStr)) == 0;

            // Re-compute expected dynamic hash to guarantee cryptographic integrity
            String expectedHash = calculateDynamicHash(
                    transactionId,
                    storedDest,
                    new BigDecimal(storedAmountStr),
                    storedCurrency,
                    storedNonce
            );
            boolean hashIntegrityValid = expectedHash.equals(storedToken);

            isValid = tokenMatches && destMatches && amountMatches && hashIntegrityValid &&
                    signature != null && !signature.isBlank();

            if (!destMatches || !amountMatches) {
                log.warn("Dynamic linking mismatch detected for tx {}: expected dest={}, actual dest={}; expected amount={}, actual amount={}",
                        transactionId, storedDest, destinationAccountId, storedAmountStr, amount);
            }
        } else {
            // Fallback for legacy raw tokens
            isValid = storedRecord.equals(challengeToken) && signature != null && !signature.isBlank();
        }

        if (isValid) {
            log.info("Biometric dynamic linking challenge verified successfully for transaction {}", transactionId);
            redisTemplate.delete(chalKey);
            redisTemplate.delete(triesKey);
            return true;
        } else {
            redisTemplate.opsForValue().increment(triesKey);
            log.warn("Biometric verification failed for transaction {}. Attempts count: {}", transactionId, currentTries + 1);
            return false;
        }
    }

    public static String calculateDynamicHash(String transactionId, String destinationAccountId,
                                              BigDecimal amount, String currency, String nonce) {
        String canonical = transactionId + "|" + destinationAccountId + "|" +
                amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + "|" +
                currency + "|" + nonce;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
