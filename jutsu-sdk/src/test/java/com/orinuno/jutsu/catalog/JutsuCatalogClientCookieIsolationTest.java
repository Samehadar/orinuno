package com.orinuno.jutsu.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Regression test for the personalised-catalog leak we hit in production: jut.su (DataLife Engine)
 * persists each user's last clicked sort order on the account and serves <em>that</em> ordering on
 * every subsequent request bearing the user's cookies — even when the URL has no sort segment
 * ({@code POST /anime/}). With {@code amateurdevideo}'s session attached to the SDK's catalog
 * calls, the default catalog response started coming back alphabetised because someone (or an
 * earlier automation run) had clicked "По алфавиту" on the website under that account.
 *
 * <p>The fix removes the {@link com.orinuno.jutsu.auth.JutsuSessionManager} dependency from {@link
 * JutsuCatalogClient} entirely — turning the "catalog flies anonymous" rule from a code convention
 * into a statically-checked invariant: the constructor doesn't accept a session manager, so a
 * future refactor cannot accidentally re-introduce the leak.
 *
 * <p>This test asserts the runtime half of the contract (no {@code Cookie} header on outbound
 * catalog requests) by intercepting the SDK's {@link WebClient} traffic via a custom {@link
 * ExchangeFunction}. The compile-time half is enforced by the constructor signature itself.
 */
class JutsuCatalogClientCookieIsolationTest {

    @Test
    @DisplayName(
            "browse() never attaches Cookie header — catalog calls fly anonymous regardless of"
                    + " what the surrounding session looks like")
    void browseNeverAttachesCookieHeader() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        ExchangeFunction stubbed =
                req -> {
                    captured.set(req);
                    return Mono.just(
                            ClientResponse.create(HttpStatus.OK)
                                    .header(
                                            HttpHeaders.CONTENT_TYPE,
                                            MediaType.TEXT_HTML_VALUE + "; charset=windows-1251")
                                    .body("var anime_page_next = false;\n")
                                    .build());
                };
        WebClient.Builder webClientBuilder = WebClient.builder().exchangeFunction(stubbed);

        JutsuConfig config = JutsuConfig.builder().userAgent("ua").build();
        JutsuRateLimiter limiter = new JutsuRateLimiter(config::rateLimitRps, null);
        JutsuDriftDetector detector = new JutsuDriftDetector();
        JutsuCatalogClient client =
                new JutsuCatalogClient(config, limiter, detector, webClientBuilder);

        JutsuCatalogPage page =
                client.browse(JutsuCatalogRequest.unfiltered(1))
                        .block(java.time.Duration.ofSeconds(5));

        assertThat(page).isNotNull();
        ClientRequest sent = captured.get();
        assertThat(sent).as("catalog client should have sent exactly one request").isNotNull();
        assertThat(sent.headers().get(HttpHeaders.COOKIE))
                .as(
                        "catalog requests must never carry a Cookie header — DLE personalises"
                                + " catalog ordering per account, so any session leakage flips"
                                + " the response from by-rating to whatever the cookie holder"
                                + " last clicked")
                .isNullOrEmpty();
        assertThat(sent.url().getPath())
                .as("empty filter must hit /anime/ — slugger must not drift")
                .isEqualTo("/anime/");
    }
}
