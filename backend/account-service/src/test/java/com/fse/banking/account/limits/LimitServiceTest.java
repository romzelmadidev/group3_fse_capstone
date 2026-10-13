package com.fse.banking.account.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fse.banking.account.limits.LimitService.Evaluation;
import com.fse.banking.account.limits.LimitService.View;
import com.fse.banking.account.model.AccountEntity;
import com.fse.banking.account.model.UserEntity;
import com.fse.banking.account.repository.AccountRepository;
import com.fse.banking.account.repository.UserRepository;
import com.fse.banking.common.enums.KycStatus;
import com.fse.banking.common.enums.UserStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Unit tests for {@link LimitService} with mocked repositories, CBS usage client and Redis.
 *
 * <p>Validates: Requirements 1.5, 4.10, 6.4
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"})
class LimitServiceTest {

    private static final String USER_ID = "user-1";
    private static final String ACCOUNT_ID = "acc-1";
    private static final String ACCOUNT_NUMBER = "1000-2000-3001";
    private static final String LOCK_KEY = "limits:lock:" + USER_ID;

    private static final LimitProperties PROPS = new LimitProperties(
            new LimitProperties.Caps(bd("5000"), bd("10000"), bd("50000")),
            new LimitProperties.Caps(bd("50000"), bd("100000"), bd("500000")));

    @Mock UserRepository userRepository;
    @Mock AccountRepository accountRepository;
    @Mock CbsUsageClient cbsUsageClient;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private UserEntity user;
    private LimitService service;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @BeforeEach
    void setUp() {
        user = UserEntity.builder().userId(USER_ID).kycStatus(KycStatus.PENDING).status(UserStatus.ACTIVE).build();
        AccountEntity account = AccountEntity.builder().accountId(ACCOUNT_ID).userId(USER_ID)
                .accountNumber(ACCOUNT_NUMBER).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountRepository.findById(ACCOUNT_NUMBER)).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumber(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
        when(accountRepository.findByUserId(USER_ID)).thenReturn(List.of(account));
        when(cbsUsageClient.fetchOutgoing(anyList(), any()))
                .thenReturn(new CbsUsageClient.Usage(bd("2500"), bd("12000")));

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class))).thenReturn(true);

        // Short poll/wait so the lock-timeout test is fast.
        service = new LimitService(userRepository, accountRepository, cbsUsageClient, new LimitPolicy(PROPS),
                redis, 5, 40);
    }

    // ---- tier follows the current KYC status (1.5, 6.4) ----

    @Test
    void changingKycStatusBetweenCallsChangesTheTier() {
        user.setKycStatus(KycStatus.PENDING);
        LimitSnapshot before = service.snapshotFor(USER_ID);

        user.setKycStatus(KycStatus.VERIFIED);
        LimitSnapshot after = service.snapshotFor(USER_ID);

        user.setKycStatus(KycStatus.PENDING);
        LimitSnapshot back = service.snapshotFor(USER_ID);

        assertEquals(LimitTier.BASIC, before.tier());
        assertEquals(bd("5000"), before.perTransferCap());
        assertEquals(LimitTier.FULLY_VERIFIED, after.tier());
        assertEquals(bd("50000"), after.perTransferCap());
        assertEquals(bd("100000"), after.dailyCap());
        assertEquals(bd("500000"), after.monthlyCap());
        assertEquals(LimitTier.BASIC, back.tier());
    }

    @Test
    void changingKycStatusChangesTheEvaluateOutcomeOnTheNextCall() {
        BigDecimal amount = bd("6000"); // above BASIC per-transfer cap, within FULLY_VERIFIED

        Evaluation basic = service.evaluate("TXN-1", ACCOUNT_ID, amount);
        assertFalse(basic.allowed());
        assertEquals(CapKind.PER_TRANSFER, basic.breach().capKind());
        assertEquals(LimitTier.BASIC, basic.breach().tier());

        user.setKycStatus(KycStatus.APPROVED);
        Evaluation verified = service.evaluate("TXN-2", ACCOUNT_ID, amount);
        assertTrue(verified.allowed());
        assertNull(verified.breach());
        assertNotNull(verified.lockToken());
    }

    @Test
    void viewForExposesKycStatusAndWindows() {
        user.setKycStatus(KycStatus.VERIFIED);

        View view = service.viewFor(USER_ID);

        assertEquals(KycStatus.VERIFIED, view.kycStatus());
        assertNotNull(view.windows());
        assertTrue(view.windows().dayEnd().isAfter(view.windows().dayStart()));
        assertTrue(view.windows().monthEnd().isAfter(view.windows().monthStart()));
    }

    // ---- snapshot uses LimitProperties caps and CBS usage ----

    @Test
    void snapshotUsesConfiguredCapsAndCbsUsage() {
        LimitSnapshot s = service.snapshotFor(USER_ID);

        assertEquals(LimitTier.BASIC, s.tier());
        assertFalse(s.restricted());
        assertEquals(bd("5000"), s.perTransferCap());
        assertEquals(bd("10000"), s.dailyCap());
        assertEquals(bd("2500"), s.dailyUsed());
        assertEquals(bd("50000"), s.monthlyCap());
        assertEquals(bd("12000"), s.monthlyUsed());
        assertNotNull(s.now());
    }

    @Test
    void usageQueryCarriesAccountIdAndNumberAndTheComputedWindows() {
        View view = service.viewFor(USER_ID);

        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<LimitPolicy.Windows> windows = ArgumentCaptor.forClass(LimitPolicy.Windows.class);
        verify(cbsUsageClient).fetchOutgoing(ids.capture(), windows.capture());
        assertEquals(List.of(ACCOUNT_ID, ACCOUNT_NUMBER), ids.getValue());
        assertEquals(view.windows(), windows.getValue());
    }

    @Test
    void restrictedUserGetsZeroCaps() {
        user.setStatus(UserStatus.LOCKED);

        LimitSnapshot s = service.snapshotFor(USER_ID);

        assertTrue(s.restricted());
        assertEquals(0, BigDecimal.ZERO.compareTo(s.perTransferCap()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.dailyCap()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.monthlyCap()));
    }

    @Test
    void userWithNoAccountsHasZeroUsageAndNoCbsCall() {
        when(accountRepository.findByUserId(USER_ID)).thenReturn(List.of());

        LimitSnapshot s = service.snapshotFor(USER_ID);

        assertEquals(0, BigDecimal.ZERO.compareTo(s.dailyUsed()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.monthlyUsed()));
        verifyNoInteractions(cbsUsageClient);
    }

    // ---- tierFor: read-only tier lookup for the risk service (2.4, 3.3, 3.4) ----

    @Test
    void tierForMapsPendingNullAndRejectedKycToBasic() {
        for (KycStatus kyc : new KycStatus[] {KycStatus.PENDING, null, KycStatus.REJECTED}) {
            user.setKycStatus(kyc);

            LimitPolicy.Resolution r = service.tierFor(ACCOUNT_ID);

            assertEquals(LimitTier.BASIC, r.tier(), String.valueOf(kyc));
            assertFalse(r.restricted());
        }
    }

    @Test
    void tierForMapsVerifiedAndApprovedKycToFullyVerified() {
        for (KycStatus kyc : new KycStatus[] {KycStatus.VERIFIED, KycStatus.APPROVED}) {
            user.setKycStatus(kyc);

            assertEquals(LimitTier.FULLY_VERIFIED, service.tierFor(ACCOUNT_ID).tier(), kyc.name());
        }
    }

    @Test
    void tierForReportsRestrictedForLockedUser() {
        user.setStatus(UserStatus.LOCKED);

        assertTrue(service.tierFor(ACCOUNT_ID).restricted());
    }

    @Test
    void tierForAcceptsAccountNumberAsWellAsAccountId() {
        user.setKycStatus(KycStatus.VERIFIED);

        assertEquals(LimitTier.FULLY_VERIFIED, service.tierFor(ACCOUNT_NUMBER).tier());
        assertEquals(LimitTier.FULLY_VERIFIED, service.tierFor(ACCOUNT_ID).tier());
    }

    @Test
    void tierForTakesNoLockAndMakesNoCbsCall() {
        service.tierFor(ACCOUNT_ID);

        verifyNoInteractions(redis);
        verifyNoInteractions(cbsUsageClient);
    }

    @Test
    void tierForFailsClosedForUnknownBlankAccountOrUser() {
        when(accountRepository.findById("nope")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumber("nope")).thenReturn(Optional.empty());
        assertThrows(LimitUnavailableException.class, () -> service.tierFor("nope"));
        assertThrows(LimitUnavailableException.class, () -> service.tierFor(null));
        assertThrows(LimitUnavailableException.class, () -> service.tierFor("  "));

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(LimitUnavailableException.class, () -> service.tierFor(ACCOUNT_ID));
    }

    // ---- fail closed (4.10) ----

    @Test
    void unknownUserFailsClosed() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThrows(LimitUnavailableException.class, () -> service.snapshotFor("ghost"));
        assertThrows(LimitUnavailableException.class, () -> service.viewFor("ghost"));
    }

    @Test
    void unknownSourceAccountFailsClosedWithoutTakingTheLock() {
        when(accountRepository.findById("nope")).thenReturn(Optional.empty());
        when(accountRepository.findByAccountNumber("nope")).thenReturn(Optional.empty());

        assertThrows(LimitUnavailableException.class, () -> service.evaluate("TXN-1", "nope", bd("1")));

        verifyNoInteractions(valueOps);
        verifyNoInteractions(cbsUsageClient);
    }

    @Test
    void blankOrNullSourceAccountFailsClosed() {
        assertThrows(LimitUnavailableException.class, () -> service.evaluate("TXN-1", null, bd("1")));
        assertThrows(LimitUnavailableException.class, () -> service.evaluate("TXN-1", "  ", bd("1")));
    }

    @Test
    void sourceAccountMayBeResolvedByAccountNumber() {
        Evaluation e = service.evaluate("TXN-1", ACCOUNT_NUMBER, bd("100"));

        assertTrue(e.allowed());
        assertTrue(e.lockToken().startsWith(USER_ID + ":"));
    }

    @Test
    void unknownUserDuringEvaluateFailsClosedAndReleasesTheLock() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(LimitUnavailableException.class, () -> service.evaluate("TXN-1", ACCOUNT_ID, bd("1")));

        verifyLockReleasedForAcquiredToken();
    }

    @Test
    void cbsFailureFailsClosedAndReleasesTheLock() {
        when(cbsUsageClient.fetchOutgoing(anyList(), any()))
                .thenThrow(new LimitUnavailableException("CBS down"));

        assertThrows(LimitUnavailableException.class, () -> service.evaluate("TXN-1", ACCOUNT_ID, bd("1")));

        verifyLockReleasedForAcquiredToken();
    }

    @Test
    void unexpectedRuntimeFailureIsWrappedAsLimitUnavailableAndReleasesTheLock() {
        when(cbsUsageClient.fetchOutgoing(anyList(), any())).thenThrow(new IllegalStateException("boom"));

        LimitUnavailableException ex = assertThrows(LimitUnavailableException.class,
                () -> service.evaluate("TXN-1", ACCOUNT_ID, bd("1")));

        assertTrue(ex.getCause() instanceof IllegalStateException);
        verifyLockReleasedForAcquiredToken();
    }

    @Test
    void lockTimeoutFailsClosedWithoutReadingUsageOrDeletingSomeoneElsesLock() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class))).thenReturn(false);

        assertThrows(LimitUnavailableException.class, () -> service.evaluate("TXN-1", ACCOUNT_ID, bd("1")));

        verify(valueOps, atLeast(2)).setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class));
        verifyNoInteractions(cbsUsageClient);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void redisErrorWhileAcquiringFailsClosed() {
        when(valueOps.setIfAbsent(eq(LOCK_KEY), any(String.class), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThrows(LimitUnavailableException.class, () -> service.evaluate("TXN-1", ACCOUNT_ID, bd("1")));

        verifyNoInteractions(cbsUsageClient);
    }

    // ---- breach: lock released, evaluation carries the breach ----

    @Test
    void breachReleasesTheLockAndCarriesTheBreach() {
        // per-transfer cap 5000 (lowest remaining) => 5001 breaches PER_TRANSFER
        Evaluation e = service.evaluate("TXN-1", ACCOUNT_ID, bd("5001"));

        assertFalse(e.allowed());
        assertNull(e.lockToken());
        Breach breach = e.breach();
        assertNotNull(breach);
        assertEquals(CapKind.PER_TRANSFER, breach.capKind());
        assertEquals(bd("5000"), breach.capValue());
        assertEquals(LimitTier.BASIC, breach.tier());
        assertFalse(breach.restricted());
        verifyLockReleasedForAcquiredToken();
    }

    @Test
    void dailyBreachUsesRemainingFromCbsUsage() {
        when(cbsUsageClient.fetchOutgoing(anyList(), any()))
                .thenReturn(new CbsUsageClient.Usage(bd("8000"), bd("12000")));

        Evaluation e = service.evaluate("TXN-1", ACCOUNT_ID, bd("3000")); // daily remaining 2000

        assertFalse(e.allowed());
        assertEquals(CapKind.DAILY, e.breach().capKind());
        assertEquals(bd("10000"), e.breach().capValue());
        assertEquals(bd("2000"), e.breach().remaining());
        verifyLockReleasedForAcquiredToken();
    }

    // ---- allow: token returned, lock held until release(token) ----

    @Test
    void allowedKeepsTheLockAndReturnsTheTokenThatWasStored() {
        Evaluation e = service.evaluate("TXN-1", ACCOUNT_ID, bd("100"));

        assertTrue(e.allowed());
        assertNull(e.breach());
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(eq(LOCK_KEY), stored.capture(), eq(Duration.ofMillis(30_000)));
        assertEquals(stored.getValue(), e.lockToken());
        assertTrue(e.lockToken().startsWith(USER_ID + ":"));
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void releaseDoesCompareAndDeleteOnTheCustomerLockKey() {
        String token = service.evaluate("TXN-1", ACCOUNT_ID, bd("100")).lockToken();

        service.release(token);

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of(LOCK_KEY)), eq(token));
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("get") && lua.contains("del") && lua.contains("ARGV[1]"), lua);
    }

    @Test
    void releaseFailureIsSwallowed() {
        doThrow(new IllegalStateException("redis down")).when(redis)
                .execute(any(RedisScript.class), anyList(), eq("user-1:abc"));

        service.release("user-1:abc");

        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("user-1:abc"));
    }

    @Test
    void malformedTokenOnReleaseIsIgnoredAndNeverThrows() {
        service.release(null);
        service.release("");
        service.release("no-separator");
        service.release(":leading-separator-only");

        verifyNoInteractions(redis);
    }

    /** Verifies the token stored by the lock acquisition was compare-and-deleted on the customer key. */
    private void verifyLockReleasedForAcquiredToken() {
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(eq(LOCK_KEY), stored.capture(), any(Duration.class));
        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_KEY)), eq(stored.getValue()));
    }
}
