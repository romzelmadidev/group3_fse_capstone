package com.fse.banking.account.limits;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Reads a customer's outgoing transfer usage from t24-mock-cbs. Every failure is a {@link LimitUnavailableException}. */
@Slf4j
@Component
public class CbsUsageClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("daily_used") BigDecimal dailyUsed,
            @JsonProperty("monthly_used") BigDecimal monthlyUsed) {
    }

    private final String cbsUrl;
    private final RestTemplate restTemplate;

    @Autowired
    public CbsUsageClient(@Value("${app.services.cbs.url}") String cbsUrl) {
        this(cbsUrl, timeoutRestTemplate());
    }

    public CbsUsageClient(String cbsUrl, RestTemplate restTemplate) {
        this.cbsUrl = cbsUrl;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate timeoutRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    public Usage fetchOutgoing(List<String> accountIds, LimitPolicy.Windows w) {
        URI uri = UriComponentsBuilder.fromUriString(cbsUrl)
                .path("/api/v1/cbs/usage/outgoing")
                .queryParam("accountIds", String.join(",", accountIds))
                .queryParam("dayStart", w.dayStart())
                .queryParam("dayEnd", w.dayEnd())
                .queryParam("monthStart", w.monthStart())
                .queryParam("monthEnd", w.monthEnd())
                .build().encode().toUri();
        try {
            Usage usage = restTemplate.getForObject(uri, Usage.class);
            if (usage == null || usage.dailyUsed() == null || usage.monthlyUsed() == null) {
                throw new LimitUnavailableException("CBS usage response was empty or incomplete");
            }
            return usage;
        } catch (LimitUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("CBS usage lookup failed: {}", e.getMessage());
            throw new LimitUnavailableException("CBS usage lookup failed: " + e.getMessage(), e);
        }
    }
}
