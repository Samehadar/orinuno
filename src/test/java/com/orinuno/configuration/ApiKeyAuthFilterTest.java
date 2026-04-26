package com.orinuno.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ApiKeyAuthFilterTest {

    private OrinunoProperties properties;
    private ApiKeyAuthFilter filter;
    private AtomicBoolean chainInvoked;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getSecurity().setApiKey("secret");
        filter = new ApiKeyAuthFilter(properties);
        chainInvoked = new AtomicBoolean(false);
        chain =
                exchange -> {
                    chainInvoked.set(true);
                    return Mono.empty();
                };
    }

    @Test
    @DisplayName("/api/v1/kodik/* requires X-API-KEY")
    void kodikPathRequiresAuth() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/kodik/list"));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked).isFalse();
    }

    @Test
    @DisplayName("/api/v1/kodik/* passes with correct key")
    void kodikPathPassesWithKey() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/kodik/list")
                                .header("X-API-KEY", "secret"));

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("/api/v1/parse/requests still requires auth")
    void parseRequestsRequiresAuth() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/parse/requests"));

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(chainInvoked).isFalse();
    }

    @Test
    @DisplayName("public path bypasses auth")
    void publicPathBypassesAuth() {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/health"));

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked).isTrue();
    }

    @Test
    @DisplayName("blank api-key disables auth")
    void blankKeyDisablesAuth() {
        properties.getSecurity().setApiKey("");
        ApiKeyAuthFilter open = new ApiKeyAuthFilter(properties);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/kodik/list"));

        open.filter(exchange, chain).block();

        assertThat(chainInvoked).isTrue();
    }
}
