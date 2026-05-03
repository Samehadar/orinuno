package com.orinuno.jutsu.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class JutsuSessionManagerTest {

    /**
     * Minimal stub {@link ExchangeFunction}. We capture the {@link ClientRequest} for
     * URL/method/header assertions but deliberately do NOT extract the form body — extracting bytes
     * from a ClientRequest's BodyInserter is awkward and brittle. Body shape is covered separately
     * by {@link JutsuSessionManager#previewLoginBody()} which builds the same payload
     * synchronously.
     */
    static final class StubExchange implements ExchangeFunction {
        final List<ClientRequest> requests = new ArrayList<>();
        private final List<ClientResponse> responses;
        private final AtomicInteger cursor = new AtomicInteger();

        StubExchange(List<ClientResponse> responses) {
            this.responses = responses;
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            int idx = Math.min(cursor.getAndIncrement(), responses.size() - 1);
            return Mono.just(responses.get(idx));
        }
    }

    private static JutsuConfig configWith(String username, String password) {
        return JutsuConfig.builder()
                .baseUrl("https://jut.su")
                .credentials(username, password)
                .userAgent("Mozilla/5.0 (sdk-test)")
                .rateLimitRps(100.0) // not the bottleneck in these tests
                .sessionTtl(Duration.ofMinutes(60))
                .loginTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static ClientResponse dleSuccess(String userId) {
        // JutsuSessionManager parses raw Set-Cookie response headers (it does not rely on
        // ClientResponse.cookies()) so we set them as headers here. The shape mirrors the live
        // probe captured during PLAYER-4 PREMIUM-1.
        return ClientResponse.create(HttpStatus.OK)
                .header("Set-Cookie", "dle_newpm=0; path=/; HttpOnly")
                .header("Set-Cookie", "dle_password=md5hash; path=/; HttpOnly")
                .header("Set-Cookie", "dle_user_id=" + userId + "; path=/; HttpOnly")
                .header("Set-Cookie", "LB_member_sc=lb_value; path=/; HttpOnly")
                .header("Set-Cookie", "PHPSESSID=phpsessid_value; path=/; HttpOnly")
                .body("<html>logged in</html>")
                .build();
    }

    private static ClientResponse anonymousResponse() {
        // No dle_user_id => SessionManager treats this as a failed login.
        return ClientResponse.create(HttpStatus.OK)
                .header("Set-Cookie", "PHPSESSID=anon123; path=/; HttpOnly")
                .body("<html>still on login form</html>")
                .build();
    }

    private JutsuSessionManager build(
            JutsuConfig config, List<ClientResponse> responses, Clock clock) {
        return build(config, new StubExchange(responses), clock);
    }

    private JutsuSessionManager build(JutsuConfig config, StubExchange stub, Clock clock) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(stub);
        return new JutsuSessionManager(
                config,
                new JutsuRateLimiter(config::rateLimitRps, new SimpleMeterRegistry()),
                builder,
                new SimpleMeterRegistry(),
                clock);
    }

    @Test
    void emptyCookieHeaderWhenCredentialsNotConfigured() {
        JutsuConfig config = configWith("", "");
        JutsuSessionManager mgr = build(config, Collections.emptyList(), Clock.systemUTC());
        assertThat(mgr.cookieHeader().blockOptional()).isEmpty();
        assertThat(mgr.peekHasCredentials()).isFalse();
    }

    @Test
    void successfulLoginCachesCookieHeader() {
        StubExchange stub = new StubExchange(List.of(dleSuccess("3829047")));
        JutsuConfig config = configWith("amateurdevideo", "hunter2");
        JutsuSessionManager mgr = build(config, stub, Clock.systemUTC());

        String cookies = mgr.cookieHeader().block();
        assertThat(cookies)
                .contains("dle_user_id=3829047")
                .contains("dle_password=md5hash")
                .contains("PHPSESSID=phpsessid_value");

        // Second call MUST hit the cache.
        String cookiesAgain = mgr.cookieHeader().block();
        assertThat(cookiesAgain).isEqualTo(cookies);
        assertThat(stub.requests).hasSize(1);
        ClientRequest req = stub.requests.get(0);
        assertThat(req.url().getPath()).isEqualTo("/");
        assertThat(req.method().name()).isEqualTo("POST");
    }

    @Test
    void invalidateForcesReloginNextCall() {
        StubExchange stub = new StubExchange(List.of(dleSuccess("100"), dleSuccess("100")));
        JutsuSessionManager mgr = build(configWith("u", "p"), stub, Clock.systemUTC());
        mgr.cookieHeader().block();
        mgr.invalidate("test");
        mgr.cookieHeader().block();
        assertThat(stub.requests).hasSize(2);
    }

    @Test
    void missingDleUserIdCookieIsTreatedAsLoginFailure() {
        StubExchange stub = new StubExchange(List.of(anonymousResponse()));
        JutsuSessionManager mgr = build(configWith("u", "p"), stub, Clock.systemUTC());
        assertThat(mgr.cookieHeader().blockOptional()).isEmpty();
        assertThat(mgr.peek()).isNull();
    }

    @Test
    void expiredSessionIsRefreshedOnNextCall() {
        JutsuConfig config =
                JutsuConfig.builder()
                        .baseUrl("https://jut.su")
                        .credentials("u", "p")
                        .userAgent("ua")
                        .rateLimitRps(100.0)
                        .sessionTtl(Duration.ofMinutes(1))
                        .loginTimeout(Duration.ofSeconds(5))
                        .build();
        StubExchange stub = new StubExchange(List.of(dleSuccess("777"), dleSuccess("777")));
        StepClock clock = new StepClock(Instant.parse("2026-01-01T00:00:00Z"));
        JutsuSessionManager mgr = build(config, stub, clock);

        mgr.cookieHeader().block();
        assertThat(stub.requests).hasSize(1);

        clock.advance(Duration.ofMinutes(2));
        mgr.cookieHeader().block();
        assertThat(stub.requests).hasSize(2);
    }

    @Test
    void absolutizeReturnsAbsoluteUrlAsIs() {
        JutsuSessionManager mgr = build(configWith("", ""), List.of(), Clock.systemUTC());
        assertThat(mgr.absolutize("https://jut.su/foo")).isEqualTo("https://jut.su/foo");
    }

    @Test
    void absolutizeAppendsBaseToRelativeUrl() {
        JutsuSessionManager mgr = build(configWith("", ""), List.of(), Clock.systemUTC());
        assertThat(mgr.absolutize("/naruto/episode-1.html"))
                .isEqualTo("https://jut.su/naruto/episode-1.html");
    }

    @Test
    void previewLoginBodyUrlEncodesSpecialChars() {
        JutsuConfig config = configWith("amateurdevideo", "5&L!s9bCk^GU*8dc");
        JutsuSessionManager mgr = build(config, List.of(), Clock.systemUTC());
        String body = mgr.previewLoginBody();
        // Java's URLEncoder follows form-urlencoded rules: '&' '!' '^' get percent-escaped, but
        // '*' is unreserved per RFC 3986 and passes through unchanged. Mirroring the actual
        // behaviour here so a regression in URLEncoder.encode() would fail loudly.
        assertThat(body)
                .contains("login_password=5%26L%21s9bCk%5EGU*8dc")
                .contains("&login=submit");
    }

    /** Minimal mutable Clock for "fast forward" without dragging in a heavyweight test util. */
    static final class StepClock extends Clock {
        private Instant now;

        StepClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
