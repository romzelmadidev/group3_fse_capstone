package com.fse.banking.account.limits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("limits")
public record LimitProperties(
        @Valid @NotNull Caps basic,
        @Valid @NotNull @Name("fully-verified") Caps fullyVerified) {

    public record Caps(
            @NotNull @PositiveOrZero @Name("per-transfer") BigDecimal perTransfer,
            @NotNull @PositiveOrZero BigDecimal daily,
            @NotNull @PositiveOrZero BigDecimal monthly) {
    }
}
