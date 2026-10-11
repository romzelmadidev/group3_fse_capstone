package com.bank.orchestrator;

import com.bank.orchestrator.service.BiometricChallengeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BiometricChallengeServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private BiometricChallengeService biometricService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        biometricService = new BiometricChallengeService(redisTemplate);
    }

    @Test
    void testDynamicLinking_GeneratesCryptographicallyBoundChallenge() {
        String token = biometricService.generateChallenge(
                "TXN-101", "ACC-TARGET", new BigDecimal("50000.00"), "PHP"
        );

        assertNotNull(token);
        assertEquals(64, token.length()); // SHA-256 hex string is 64 characters

        // Verify saved in Redis with 5 min TTL
        verify(valueOperations, times(1)).set(
                eq("tx:bio:chal:TXN-101"),
                contains("ACC-TARGET|50000.00|PHP|"),
                any(Duration.class)
        );
        verify(valueOperations, times(1)).set(
                eq("tx:bio:tries:TXN-101"),
                eq("0"),
                any(Duration.class)
        );
    }

    @Test
    void testDynamicLinking_SuccessfulVerification() {
        String txId = "TXN-202";
        String destAcc = "ACC-BENEFICIARY";
        BigDecimal amount = new BigDecimal("75000.00");
        String currency = "PHP";
        String nonce = "test-nonce-123";

        String expectedToken = BiometricChallengeService.calculateDynamicHash(txId, destAcc, amount, currency, nonce);
        String storedRecord = destAcc + "|75000.00|PHP|" + nonce + "|" + expectedToken;

        when(valueOperations.get("tx:bio:tries:" + txId)).thenReturn("0");
        when(valueOperations.get("tx:bio:chal:" + txId)).thenReturn(storedRecord);

        boolean result = biometricService.verifyChallenge(
                txId, destAcc, amount, expectedToken, "VALID_SIGNATURE_BYTES", "DEV-01"
        );

        assertTrue(result);
        verify(redisTemplate, times(1)).delete("tx:bio:chal:" + txId);
        verify(redisTemplate, times(1)).delete("tx:bio:tries:" + txId);
    }

    @Test
    void testDynamicLinking_TamperedAmount_RejectsVerification() {
        String txId = "TXN-303";
        String destAcc = "ACC-BENEFICIARY";
        BigDecimal origAmount = new BigDecimal("5000.00");
        BigDecimal tamperedAmount = new BigDecimal("50000.00");
        String currency = "PHP";
        String nonce = "test-nonce-456";

        String token = BiometricChallengeService.calculateDynamicHash(txId, destAcc, origAmount, currency, nonce);
        String storedRecord = destAcc + "|5000.00|PHP|" + nonce + "|" + token;

        when(valueOperations.get("tx:bio:tries:" + txId)).thenReturn("0");
        when(valueOperations.get("tx:bio:chal:" + txId)).thenReturn(storedRecord);

        // Verification submitted with tampered 50,000.00 instead of 5,000.00
        boolean result = biometricService.verifyChallenge(
                txId, destAcc, tamperedAmount, token, "VALID_SIGNATURE", "DEV-01"
        );

        assertFalse(result);
        verify(valueOperations, times(1)).increment("tx:bio:tries:" + txId);
    }

    @Test
    void testDynamicLinking_TamperedDestination_RejectsVerification() {
        String txId = "TXN-404";
        String origDest = "ACC-JUAN";
        String tamperedDest = "ACC-HACKER";
        BigDecimal amount = new BigDecimal("10000.00");
        String currency = "PHP";
        String nonce = "test-nonce-789";

        String token = BiometricChallengeService.calculateDynamicHash(txId, origDest, amount, currency, nonce);
        String storedRecord = origDest + "|10000.00|PHP|" + nonce + "|" + token;

        when(valueOperations.get("tx:bio:tries:" + txId)).thenReturn("0");
        when(valueOperations.get("tx:bio:chal:" + txId)).thenReturn(storedRecord);

        // Verification submitted with tampered recipient ACC-HACKER
        boolean result = biometricService.verifyChallenge(
                txId, tamperedDest, amount, token, "VALID_SIGNATURE", "DEV-01"
        );

        assertFalse(result);
        verify(valueOperations, times(1)).increment("tx:bio:tries:" + txId);
    }

    @Test
    void testDynamicLinking_MaxTriesExceeded_ThrowsException() {
        String txId = "TXN-505";
        when(valueOperations.get("tx:bio:tries:" + txId)).thenReturn("3");

        assertThrows(IllegalStateException.class, () ->
                biometricService.verifyChallenge(txId, "ACC-1", new BigDecimal("100.00"), "TOKEN", "SIG", "DEV-1")
        );
    }
}
