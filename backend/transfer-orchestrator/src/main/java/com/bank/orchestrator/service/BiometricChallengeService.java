package com.bank.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
        String challengeToken = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(CHAL_PREFIX + transactionId, challengeToken, CHAL_TTL);
        redisTemplate.opsForValue().set(TRIES_PREFIX + transactionId, "0", CHAL_TTL);
        log.info("Generated biometric challenge for transaction {}", transactionId);
        return challengeToken;
    }

    public boolean verifyChallenge(String transactionId, String challengeToken, String signature, String deviceId) {
        String triesKey = TRIES_PREFIX + transactionId;
        String chalKey = CHAL_PREFIX + transactionId;

        String currentTriesStr = redisTemplate.opsForValue().get(triesKey);
        int currentTries = currentTriesStr != null ? Integer.parseInt(currentTriesStr) : 0;

        if (currentTries >= MAX_TRIES) {
            log.warn("Biometric verification attempts exceeded for transaction {}", transactionId);
            throw new IllegalStateException("BIOMETRIC_ATTEMPTS_EXCEEDED: Maximum of 3 attempts reached");
        }

        String storedChallenge = redisTemplate.opsForValue().get(chalKey);
        if (storedChallenge == null) {
            throw new IllegalArgumentException("Biometric challenge expired or not found for transaction " + transactionId);
        }

        // Verify token match and non-empty cryptographic assertion signature
        boolean isValid = storedChallenge.equals(challengeToken) && signature != null && !signature.isBlank();

        if (isValid) {
            log.info("Biometric challenge verified successfully for transaction {}", transactionId);
            redisTemplate.delete(chalKey);
            redisTemplate.delete(triesKey);
            return true;
        } else {
            redisTemplate.opsForValue().increment(triesKey);
            log.warn("Biometric verification failed for transaction {}. Attempts count: {}", transactionId, currentTries + 1);
            return false;
        }
    }
}
