package com.orinuno.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ParseInboundRateLimitFilterTest {

    private OrinunoProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private ParseInboundRateLimiter rateLimiter;
    private ParseInboundRateLimitFilter filter;
    private AtomicInteger chainInvoked;
    private WebFilterChain chain;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getParse().getInboundRateLimit().setRequestsPerMinute(2);
        meterRegistry = new SimpleMeterRegistry();
        rateLimiter = new ParseInboundRateLimiter(properties, meterRegistry);
        objectMapper = new ObjectMapper();
        filter = new ParseInboundRateLimitFilter(rateLimiter, objectMapper);
        chainInvoked = new AtomicInteger(0);
        chain =
                exchange -> {
                    chainInvoked.incrementAndGet();
                    return Mono.empty();
                };
    }

    @Test
    @DisplayName("non-target path bypasses filter entirely")
    void nonTargetPathBypasses() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/health"));

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked.get()).isEqualTo(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("GET /api/v1/parse/requests bypasses filter (only POST is gated)")
    void getMethodBypasses() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/parse/requests"));

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST without X-Created-By returns 400")
    void missingHeaderReturns400() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/parse/requests"));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(chainInvoked.get()).isZero();
    }

    @Test
    @DisplayName("POST with blank X-Created-By returns 400")
    void blankHeaderReturns400() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/v1/parse/requests")
                                .header(ParseInboundRateLimitFilter.CREATED_BY_HEADER, "  "));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(chainInvoked.get()).isZero();
    }

    @Test
    @DisplayName("POST under budget passes through")
    void underBudgetPasses() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/v1/parse/requests")
                                .header(
                                        ParseInboundRateLimitFilter.CREATED_BY_HEADER,
                                        "parser-kodik"));

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked.get()).isEqualTo(1);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("over-budget submission returns 429 with Retry-After + increments counter")
    void overBudgetReturns429() {
        for (int i = 0; i < 2; i++) {
            MockServerWebExchange ok =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post("/api/v1/parse/requests")
                                    .header(
                                            ParseInboundRateLimitFilter.CREATED_BY_HEADER,
                                            "parser-kodik"));
            filter.filter(ok, chain).block();
        }
        MockServerWebExchange overflow =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/v1/parse/requests")
                                .header(
                                        ParseInboundRateLimitFilter.CREATED_BY_HEADER,
                                        "parser-kodik"));

        filter.filter(overflow, chain).block();

        assertThat(overflow.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overflow.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isNotNull();
        assertThat(chainInvoked.get()).isEqualTo(2);

        Counter counter =
                meterRegistry
                        .find("orinuno.inbound.throttle")
                        .tag("consumer", "parser-kodik")
                        .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("disabled limiter still validates header and never throttles")
    void disabledLimiterStillValidatesHeader() {
        properties.getParse().getInboundRateLimit().setEnabled(false);
        ParseInboundRateLimiter disabled = new ParseInboundRateLimiter(properties, meterRegistry);
        ParseInboundRateLimitFilter freshFilter =
                new ParseInboundRateLimitFilter(disabled, objectMapper);

        MockServerWebExchange noHeader =
                MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/parse/requests"));
        freshFilter.filter(noHeader, chain).block();
        assertThat(noHeader.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        for (int i = 0; i < 5; i++) {
            MockServerWebExchange ok =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post("/api/v1/parse/requests")
                                    .header(
                                            ParseInboundRateLimitFilter.CREATED_BY_HEADER,
                                            "stress-test"));
            freshFilter.filter(ok, chain).block();
        }
        assertThat(chainInvoked.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("buckets are isolated per consumer")
    void bucketsAreIsolatedPerConsumer() {
        for (int i = 0; i < 2; i++) {
            MockServerWebExchange aOk =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.post("/api/v1/parse/requests")
                                    .header(
                                            ParseInboundRateLimitFilter.CREATED_BY_HEADER,
                                            "consumer-a"));
            filter.filter(aOk, chain).block();
        }
        MockServerWebExchange bOk =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/api/v1/parse/requests")
                                .header(
                                        ParseInboundRateLimitFilter.CREATED_BY_HEADER,
                                        "consumer-b"));

        filter.filter(bOk, chain).block();

        assertThat(chainInvoked.get()).isEqualTo(3);
        assertThat(bOk.getResponse().getStatusCode()).isNull();
    }
}
