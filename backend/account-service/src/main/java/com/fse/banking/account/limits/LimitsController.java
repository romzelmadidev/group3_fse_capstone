package com.fse.banking.account.limits;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fse.banking.account.security.JwtProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Limits read endpoint (JWT, caller only) and the internal evaluate/release endpoints used by the
 * transfer orchestrator, plus the read-only tier lookup used by the risk service. Internal endpoints are unauthenticated by design: the gateway does not route
 * {@code /internal/**}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LimitsController {

    private final LimitService limitService;
    private final JwtProvider jwtProvider;

    @GetMapping("/api/v1/accounts/me/limits")
    public ResponseEntity<LimitsResponse> myLimits(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String userId = extractCallerId(authHeader, null);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(LimitsResponse.from(limitService.viewFor(userId)));
        } catch (LimitUnavailableException e) {
            log.warn("Limits unavailable for user {}: {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @PostMapping("/internal/limits/evaluate")
    public ResponseEntity<EvaluateResponse> evaluate(@Valid @RequestBody EvaluateRequest request) {
        try {
            LimitService.Evaluation result = limitService.evaluate(
                    request.transactionId(), request.sourceAccountId(), request.amount());
            return ResponseEntity.ok(result.allowed()
                    ? new EvaluateResponse(true, result.lockToken(), null)
                    : new EvaluateResponse(false, null, result.breach()));
        } catch (LimitUnavailableException e) {
            log.warn("Limit evaluation unavailable for transaction {}: {}", request.transactionId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @GetMapping("/internal/limits/tier")
    public ResponseEntity<LimitPolicy.Resolution> tier(@RequestParam String accountId) {
        try {
            return ResponseEntity.ok(limitService.tierFor(accountId));
        } catch (LimitUnavailableException e) {
            log.warn("Tier unavailable for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @PostMapping("/internal/limits/release")
    public ResponseEntity<Void> release(@Valid @RequestBody ReleaseRequest request) {
        limitService.release(request.lockToken());
        return ResponseEntity.ok().build();
    }

    private String extractCallerId(String authHeader, String defaultId) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtProvider.validateToken(token)) {
                return jwtProvider.getUserId(token);
            }
        }
        return defaultId;
    }

    public record EvaluateRequest(
            @NotBlank String transactionId,
            @NotBlank String sourceAccountId,
            @NotNull @Positive BigDecimal amount) {
    }

    public record ReleaseRequest(@NotBlank String lockToken) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvaluateResponse(boolean allowed, String lockToken, Breach breach) {
    }

    public record LimitsResponse(
            @JsonProperty("tier") LimitTier tier,
            @JsonProperty("restricted") boolean restricted,
            @JsonProperty("kyc_status") String kycStatus,
            @JsonProperty("currency") String currency,
            @JsonProperty("per_transfer_cap") BigDecimal perTransferCap,
            @JsonProperty("daily") Window daily,
            @JsonProperty("monthly") Window monthly) {

        static LimitsResponse from(LimitService.View view) {
            LimitSnapshot s = view.snapshot();
            return new LimitsResponse(
                    s.tier(),
                    s.restricted(),
                    view.kycStatus() == null ? "PENDING" : view.kycStatus().name(),
                    "PHP",
                    money(s.perTransferCap()),
                    Window.of(s.dailyCap(), s.dailyUsed(), view.windows().dayEnd()),
                    Window.of(s.monthlyCap(), s.monthlyUsed(), view.windows().monthEnd()));
        }
    }

    public record Window(
            @JsonProperty("cap") BigDecimal cap,
            @JsonProperty("used") BigDecimal used,
            @JsonProperty("remaining") BigDecimal remaining,
            @JsonProperty("resets_at") String resetsAt) {

        static Window of(BigDecimal cap, BigDecimal used, Instant resetsAt) {
            return new Window(
                    money(cap),
                    money(used),
                    money(LimitPolicy.remaining(cap, used)),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(resetsAt.atZone(LimitPolicy.ZONE)));
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
