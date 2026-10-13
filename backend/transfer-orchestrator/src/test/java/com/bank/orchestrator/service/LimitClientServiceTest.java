package com.bank.orchestrator.service;

import com.bank.orchestrator.exception.LimitCheckUnavailableException;
import com.bank.orchestrator.exception.LimitExceededException;
import com.bank.orchestrator.service.LimitClientService.LimitEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validates: Requirements 4.10 (fail closed on any unusable Limit_Service answer). */
class LimitClientServiceTest {

    private static final String BASE_URL = "http://account-service:8081";
    private static final BigDecimal AMOUNT = new BigDecimal("150.25");

    private final List<ClientRequest> requests = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();

    private Function<ClientRequest, Mono<ClientResponse>> responder;

    @BeforeEach
    void setUp() {
        requests.clear();
        requestBodies.clear();
        responder = r -> json(HttpStatus.OK, "{}");
    }

    private LimitClientService service() {
        ExchangeFunction exchange = request -> {
            requests.add(request);
            MockClientHttpRequest mock = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(mock, ExchangeStrategies.withDefaults()).block();
            requestBodies.add(mock.getBodyAsString().block());
            return responder.apply(request);
        };
        return new LimitClientService(WebClient.builder().exchangeFunction(exchange), BASE_URL);
    }

    private static Mono<ClientResponse> json(HttpStatus status, String body) {
        return Mono.just(ClientResponse.create(status, ExchangeStrategies.withDefaults())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
    }

    private static Mono<ClientResponse> noBody(HttpStatus status) {
        return Mono.just(ClientResponse.create(status, ExchangeStrategies.withDefaults()).build());
    }

    private void assertFailsClosed() {
        assertThatThrownBy(() -> service().evaluate("tx-1", "acc-1", AMOUNT))
                .isInstanceOf(LimitCheckUnavailableException.class)
                .satisfies(e -> assertThat(((LimitCheckUnavailableException) e).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ---- fail-closed cases ------------------------------------------------------------

    @Test
    void timeoutFailsClosed() {
        responder = r -> Mono.never();
        long start = System.nanoTime();
        assertFailsClosed();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isBetween(1500L, 6000L);
    }

    @Test
    void connectionErrorFailsClosed() {
        responder = r -> Mono.error(new IllegalStateException("connection refused"));
        assertFailsClosed();
    }

    @Test
    void serverErrorFailsClosed() {
        responder = r -> json(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");
        assertFailsClosed();
    }

    @Test
    void clientErrorFailsClosed() {
        responder = r -> json(HttpStatus.BAD_REQUEST, "{\"error\":\"bad\"}");
        assertFailsClosed();
    }

    @Test
    void non200SuccessStatusFailsClosed() {
        responder = r -> json(HttpStatus.ACCEPTED, "{\"allowed\":true,\"lockToken\":\"tok-1\"}");
        assertFailsClosed();
    }

    @Test
    void malformedJsonFailsClosed() {
        responder = r -> json(HttpStatus.OK, "{not valid json");
        assertFailsClosed();
    }

    @Test
    void emptyBodyFailsClosed() {
        responder = r -> noBody(HttpStatus.OK);
        assertFailsClosed();
    }

    @Test
    void allowedWithoutLockTokenFailsClosed() {
        responder = r -> json(HttpStatus.OK, "{\"allowed\":true}");
        assertFailsClosed();
    }

    @Test
    void allowedWithBlankLockTokenFailsClosed() {
        responder = r -> json(HttpStatus.OK, "{\"allowed\":true,\"lockToken\":\"  \"}");
        assertFailsClosed();
    }

    @Test
    void rejectedWithoutBreachFailsClosed() {
        responder = r -> json(HttpStatus.OK, "{\"allowed\":false}");
        assertFailsClosed();
    }

    @Test
    void rejectedWithBreachMissingCapKindFailsClosed() {
        responder = r -> json(HttpStatus.OK, "{\"allowed\":false,\"breach\":{\"tier\":\"TIER_1\"}}");
        assertFailsClosed();
    }

    // ---- success / breach -------------------------------------------------------------

    @Test
    void allowedBodyReturnsEvaluationWithToken() {
        responder = r -> json(HttpStatus.OK, "{\"allowed\":true,\"lockToken\":\"tok-123\"}");

        LimitEvaluation evaluation = service().evaluate("tx-1", "acc-1", AMOUNT);

        assertThat(evaluation.lockToken()).isEqualTo("tok-123");
    }

    @Test
    void breachBodyThrowsLimitExceededCarryingAllFields() {
        responder = r -> json(HttpStatus.OK, "{\"allowed\":false,\"breach\":{"
                + "\"capKind\":\"DAILY\",\"capValue\":1000.00,\"remaining\":49.75,"
                + "\"tier\":\"TIER_1\",\"restricted\":true}}");

        assertThatThrownBy(() -> service().evaluate("tx-1", "acc-1", AMOUNT))
                .isInstanceOfSatisfying(LimitExceededException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(e.getCapKind()).isEqualTo("DAILY");
                    assertThat(e.getCapValue()).isEqualByComparingTo("1000.00");
                    assertThat(e.getRemaining()).isEqualByComparingTo("49.75");
                    assertThat(e.getTier()).isEqualTo("TIER_1");
                    assertThat(e.isRestricted()).isTrue();
                });
    }

    // ---- request shape ----------------------------------------------------------------

    @Test
    void evaluatePostsCamelCaseBodyToInternalEvaluateEndpoint() {
        responder = r -> json(HttpStatus.OK, "{\"allowed\":true,\"lockToken\":\"tok-1\"}");

        service().evaluate("tx-1", "acc-1", AMOUNT);

        assertThat(requests).hasSize(1);
        ClientRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).isEqualTo(URI.create(BASE_URL + "/internal/limits/evaluate"));

        String body = requestBodies.get(0);
        assertThat(body)
                .contains("\"transactionId\":\"tx-1\"")
                .contains("\"sourceAccountId\":\"acc-1\"")
                .contains("\"amount\":150.25")
                .doesNotContain("transaction_id")
                .doesNotContain("source_account_id");
    }

    // ---- release ----------------------------------------------------------------------

    @Test
    void releasePostsTokenToInternalReleaseEndpoint() {
        responder = r -> noBody(HttpStatus.NO_CONTENT);

        service().release("tok-abc");

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).method()).isEqualTo(HttpMethod.POST);
        assertThat(requests.get(0).url()).isEqualTo(URI.create(BASE_URL + "/internal/limits/release"));
        assertThat(requestBodies.get(0)).contains("\"lockToken\":\"tok-abc\"");
    }

    @Test
    void releaseSwallowsServerError() {
        responder = r -> json(HttpStatus.INTERNAL_SERVER_ERROR, "{}");

        assertThatCode(() -> service().release("tok-abc")).doesNotThrowAnyException();
        assertThat(requests).hasSize(1);
    }

    @Test
    void releaseSwallowsConnectionError() {
        responder = r -> Mono.error(new IllegalStateException("connection refused"));

        assertThatCode(() -> service().release("tok-abc")).doesNotThrowAnyException();
        assertThat(requests).hasSize(1);
    }

    @Test
    void releaseSwallowsTimeout() {
        responder = r -> Mono.never();

        assertThatCode(() -> service().release("tok-abc")).doesNotThrowAnyException();
    }
}
