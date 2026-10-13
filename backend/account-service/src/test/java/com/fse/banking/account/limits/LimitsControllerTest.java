package com.fse.banking.account.limits;

import com.fse.banking.account.exception.GlobalExceptionHandler;
import com.fse.banking.account.security.JwtProvider;
import com.fse.banking.common.enums.KycStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LimitsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class LimitsControllerTest {

    private static final String LIMITS_URL = "/api/v1/accounts/me/limits";
    private static final String EVALUATE_URL = "/internal/limits/evaluate";
    private static final String RELEASE_URL = "/internal/limits/release";
    private static final String TIER_URL = "/internal/limits/tier";
    private static final String EVALUATE_BODY =
            "{\"transactionId\":\"TX-1\",\"sourceAccountId\":\"ACC-1\",\"amount\":1500.00}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LimitService limitService;

    @MockBean
    private JwtProvider jwtProvider;

    private LimitService.View view() {
        LimitSnapshot snapshot = new LimitSnapshot(
                LimitTier.FULLY_VERIFIED, false,
                new BigDecimal("50000"),
                new BigDecimal("100000"), new BigDecimal("12000.5"),
                new BigDecimal("500000"), new BigDecimal("40000"),
                Instant.parse("2026-03-10T04:00:00Z"));
        LimitPolicy.Windows windows = new LimitPolicy.Windows(
                Instant.parse("2026-03-09T16:00:00Z"), Instant.parse("2026-03-10T16:00:00Z"),
                Instant.parse("2026-02-28T16:00:00Z"), Instant.parse("2026-03-31T16:00:00Z"));
        return new LimitService.View(snapshot, KycStatus.VERIFIED, windows);
    }

    @Test
    @DisplayName("GET limits returns 401 without an Authorization header and never calls the service")
    void limits_unauthorized_whenMissingAuth() throws Exception {
        mockMvc.perform(get(LIMITS_URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(limitService);
    }

    @Test
    @DisplayName("GET limits returns 401 when the token is invalid and never calls the service")
    void limits_unauthorized_whenInvalidToken() throws Exception {
        when(jwtProvider.validateToken("bad-token")).thenReturn(false);

        mockMvc.perform(get(LIMITS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer bad-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(limitService);
    }

    @Test
    @DisplayName("GET limits uses the token subject and returns the snake_case snapshot")
    void limits_success_snakeCaseJson() throws Exception {
        when(jwtProvider.validateToken("good")).thenReturn(true);
        when(jwtProvider.getUserId("good")).thenReturn("USR-100001");
        when(limitService.viewFor("USR-100001")).thenReturn(view());

        mockMvc.perform(get(LIMITS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FULLY_VERIFIED"))
                .andExpect(jsonPath("$.restricted").value(false))
                .andExpect(jsonPath("$.kyc_status").value("VERIFIED"))
                .andExpect(jsonPath("$.currency").value("PHP"))
                .andExpect(jsonPath("$.per_transfer_cap").value(50000.0))
                .andExpect(jsonPath("$.daily.cap").value(100000.0))
                .andExpect(jsonPath("$.daily.used").value(12000.5))
                .andExpect(jsonPath("$.daily.remaining").value(87999.5))
                .andExpect(jsonPath("$.daily.resets_at").value("2026-03-11T00:00:00+08:00"))
                .andExpect(jsonPath("$.monthly.cap").value(500000.0))
                .andExpect(jsonPath("$.monthly.used").value(40000.0))
                .andExpect(jsonPath("$.monthly.remaining").value(460000.0))
                .andExpect(jsonPath("$.monthly.resets_at").value("2026-04-01T00:00:00+08:00"));

        verify(limitService).viewFor("USR-100001");
    }

    @Test
    @DisplayName("GET limits ignores any client-supplied user id")
    void limits_ignoresClientSuppliedUserId() throws Exception {
        when(jwtProvider.validateToken("good")).thenReturn(true);
        when(jwtProvider.getUserId("good")).thenReturn("USR-100001");
        when(limitService.viewFor("USR-100001")).thenReturn(view());

        mockMvc.perform(get(LIMITS_URL)
                        .param("userId", "USR-EVIL")
                        .param("user_id", "USR-EVIL")
                        .header("X-User-Id", "USR-EVIL")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer good"))
                .andExpect(status().isOk());

        verify(limitService).viewFor("USR-100001");
        verify(limitService, never()).viewFor("USR-EVIL");
    }

    @Test
    @DisplayName("GET limits returns 503 when limits are unavailable")
    void limits_serviceUnavailable() throws Exception {
        when(jwtProvider.validateToken("good")).thenReturn(true);
        when(jwtProvider.getUserId("good")).thenReturn("USR-100001");
        when(limitService.viewFor("USR-100001")).thenThrow(new LimitUnavailableException("CBS down"));

        mockMvc.perform(get(LIMITS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer good"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("POST evaluate returns allowed:true with the lock token when within limits")
    void evaluate_allowed() throws Exception {
        when(limitService.evaluate("TX-1", "ACC-1", new BigDecimal("1500.00")))
                .thenReturn(new LimitService.Evaluation(true, null, "USR-1:abc"));

        mockMvc.perform(post(EVALUATE_URL).contentType(MediaType.APPLICATION_JSON).content(EVALUATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.lockToken").value("USR-1:abc"))
                .andExpect(jsonPath("$.breach").doesNotExist());
    }

    @Test
    @DisplayName("POST evaluate returns allowed:false with breach details when a cap is exceeded")
    void evaluate_breach() throws Exception {
        Breach breach = new Breach(CapKind.DAILY, new BigDecimal("100000"), new BigDecimal("1000"),
                LimitTier.BASIC, false);
        when(limitService.evaluate("TX-1", "ACC-1", new BigDecimal("1500.00")))
                .thenReturn(new LimitService.Evaluation(false, breach, null));

        mockMvc.perform(post(EVALUATE_URL).contentType(MediaType.APPLICATION_JSON).content(EVALUATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.lockToken").doesNotExist())
                .andExpect(jsonPath("$.breach.capKind").value("DAILY"))
                .andExpect(jsonPath("$.breach.capValue").value(100000))
                .andExpect(jsonPath("$.breach.remaining").value(1000))
                .andExpect(jsonPath("$.breach.tier").value("BASIC"))
                .andExpect(jsonPath("$.breach.restricted").value(false));
    }

    @Test
    @DisplayName("POST evaluate returns 503 when limits are unavailable")
    void evaluate_unavailable() throws Exception {
        when(limitService.evaluate(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new LimitUnavailableException("Unknown source account"));

        mockMvc.perform(post(EVALUATE_URL).contentType(MediaType.APPLICATION_JSON).content(EVALUATE_BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("POST evaluate returns 400 for blank ids, missing or non-positive amounts")
    void evaluate_invalidBody() throws Exception {
        String[] bodies = {
                "{\"transactionId\":\"\",\"sourceAccountId\":\"ACC-1\",\"amount\":10}",
                "{\"transactionId\":\"TX-1\",\"sourceAccountId\":\" \",\"amount\":10}",
                "{\"transactionId\":\"TX-1\",\"sourceAccountId\":\"ACC-1\"}",
                "{\"transactionId\":\"TX-1\",\"sourceAccountId\":\"ACC-1\",\"amount\":0}",
                "{\"transactionId\":\"TX-1\",\"sourceAccountId\":\"ACC-1\",\"amount\":-5}"
        };
        for (String body : bodies) {
            mockMvc.perform(post(EVALUATE_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(limitService);
    }

    @Test
    @DisplayName("GET tier returns the tier and restricted flag for the account")
    void tier_basic() throws Exception {
        when(limitService.tierFor("ACC-1")).thenReturn(new LimitPolicy.Resolution(LimitTier.BASIC, false));

        mockMvc.perform(get(TIER_URL).param("accountId", "ACC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("BASIC"))
                .andExpect(jsonPath("$.restricted").value(false));
    }

    @Test
    @DisplayName("GET tier reports restricted:true for a restricted account")
    void tier_restricted() throws Exception {
        when(limitService.tierFor("ACC-1")).thenReturn(new LimitPolicy.Resolution(LimitTier.BASIC, true));

        mockMvc.perform(get(TIER_URL).param("accountId", "ACC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("BASIC"))
                .andExpect(jsonPath("$.restricted").value(true));
    }

    @Test
    @DisplayName("GET tier returns 503 when the tier cannot be resolved")
    void tier_unavailable() throws Exception {
        when(limitService.tierFor("ACC-1")).thenThrow(new LimitUnavailableException("Unknown source account"));

        mockMvc.perform(get(TIER_URL).param("accountId", "ACC-1"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("POST release returns 200 and delegates to LimitService.release")
    void release_delegates() throws Exception {
        mockMvc.perform(post(RELEASE_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockToken\":\"USR-1:abc\"}"))
                .andExpect(status().isOk());

        verify(limitService).release("USR-1:abc");
    }

    @Test
    @DisplayName("POST release returns 400 when the lock token is blank")
    void release_invalidBody() throws Exception {
        mockMvc.perform(post(RELEASE_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockToken\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(limitService);
    }
}
