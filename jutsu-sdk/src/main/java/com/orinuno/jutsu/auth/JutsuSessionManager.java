package com.orinuno.jutsu.auth;

import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * jut.su uses DataLife Engine (DLE) authentication: the homepage form posts {@code login_name /
 * login_password / login=submit} to {@code /} and the response sets four DLE-flavoured cookies
 * ({@code dle_user_id}, {@code dle_password} = md5 of password, {@code dle_newpm}, {@code
 * LB_member_sc}) plus a rolling {@code PHPSESSID}. Sending those cookies on subsequent GETs flips
 * the episode HTML from "{@code pixel.png} placeholders + {@code tab_need_plus} overlay" to "real
 * {@code yandexwebcache.org} CDN URLs signed with {@code derou=$dle_user_id}".
 *
 * <p>This class owns the cookie jar:
 *
 * <ul>
 *   <li>Lazy first-time login on demand (so consumers can boot without credentials configured, just
 *       with the JutSu decoder permanently in anonymous mode).
 *   <li>Sticky cookie header reused across all jut.su requests until proactive TTL expiry or an
 *       explicit {@link #invalidate(String)} call from the decoder when it sees a premium-marker on
 *       a page we should be authenticated for.
 *   <li>Single-flight relogin via {@link AtomicReference} + {@code synchronized} — a burst of
 *       parallel decoders sharing the same expired session triggers exactly one POST {@code /} and
 *       all of them resume on the same fresh cookie.
 *   <li>Every outbound call (login + each subsequent decode) is gated by {@link JutsuRateLimiter}
 *       so we never speed past the configured RPS, even during the login burst.
 * </ul>
 *
 * <p>What this class intentionally does NOT do: persist cookies to disk. The cookie jar is RAM-only
 * because (a) the DLE cookies are tied to the originating IP, so a cross-restart cache wouldn't
 * survive a pod move anyway, and (b) writing the {@code dle_password} md5 to disk would create a
 * second secret to manage. Cold-start latency is one extra POST.
 */
@Slf4j
public final class JutsuSessionManager {

    private final JutsuConfig config;
    private final JutsuRateLimiter rateLimiter;
    private final Clock clock;

    private final AtomicReference<CachedSession> cached = new AtomicReference<>(null);
    private final Counter loginAttempts;
    private final Counter loginFailures;
    private final Counter invalidations;
    private final WebClient httpClient;

    public JutsuSessionManager(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            WebClient.Builder webClientBuilder,
            @Nullable MeterRegistry meterRegistry) {
        this(config, rateLimiter, webClientBuilder, meterRegistry, Clock.systemUTC());
    }

    JutsuSessionManager(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            WebClient.Builder webClientBuilder,
            @Nullable MeterRegistry meterRegistry,
            Clock clock) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        MeterRegistry registry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.loginAttempts =
                Counter.builder("orinuno.providers.jutsu.login.attempts.total")
                        .description("DLE login POSTs to jut.su (success + failure)")
                        .register(registry);
        this.loginFailures =
                Counter.builder("orinuno.providers.jutsu.login.failures.total")
                        .description("DLE login POSTs that did not return the expected cookies")
                        .register(registry);
        this.invalidations =
                Counter.builder("orinuno.providers.jutsu.session.invalidations.total")
                        .description("Times the decoder asked us to discard the cached cookie jar")
                        .tags(Tags.empty())
                        .register(registry);

        // Build a dedicated WebClient that follows redirects and uses the configured User-Agent.
        // We cannot reuse the injected builder verbatim because Spring's default WebClient does
        // NOT follow redirects, and the DLE login response is sometimes a 302 → /.
        ReactorClientHttpConnector connector =
                new ReactorClientHttpConnector(
                        HttpClient.create()
                                .followRedirect(true)
                                .compress(true)
                                .responseTimeout(config.loginTimeout()));
        this.httpClient =
                webClientBuilder
                        .clone()
                        .baseUrl(config.baseUrl())
                        .clientConnector(connector)
                        .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                        .defaultHeader(
                                HttpHeaders.ACCEPT,
                                "text/html,application/xhtml+xml,application/xml;q=0.9,"
                                        + "image/avif,image/webp,*/*;q=0.8")
                        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8")
                        .build();
    }

    /** Returns the {@code Cookie:} header value to send with the next jut.su request, or empty. */
    public Mono<String> cookieHeader() {
        if (!config.hasCredentials()) {
            return Mono.empty();
        }
        CachedSession current = cached.get();
        if (current != null && !current.expired(clock, config.sessionTtl())) {
            return Mono.just(current.cookieHeader);
        }
        return loginAndCache().map(s -> s.cookieHeader).onErrorResume(ex -> Mono.empty());
    }

    /**
     * Drop the cached session so the next {@link #cookieHeader()} call performs a fresh login.
     * Called by the decoder when it sees a premium marker on a page that should have been
     * authenticated — that's the strongest signal the session expired or got invalidated
     * server-side.
     *
     * @param reason short, human-readable cause used in the metric tag and log line
     */
    public void invalidate(String reason) {
        CachedSession previous = cached.getAndSet(null);
        if (previous != null) {
            invalidations.increment();
            log.info(
                    "🔄 JutSu session invalidated (reason={}, age={}s)",
                    reason,
                    Duration.between(previous.loggedInAt, Instant.now(clock)).toSeconds());
        }
    }

    private synchronized Mono<CachedSession> loginAndCache() {
        CachedSession current = cached.get();
        if (current != null && !current.expired(clock, config.sessionTtl())) {
            return Mono.just(current);
        }
        return rateLimiter
                .acquire()
                .then(Mono.defer(this::performLogin))
                .doOnNext(s -> cached.set(s))
                .doOnNext(
                        s ->
                                log.info(
                                        "🔐 JutSu login successful: dle_user_id={} cookies={}",
                                        s.dleUserId,
                                        s.cookieNames));
    }

    private Mono<CachedSession> performLogin() {
        loginAttempts.increment();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("login_name", config.username());
        form.add("login_password", config.password());
        form.add("login", "submit");
        return httpClient
                .post()
                .uri("/")
                .header(HttpHeaders.ORIGIN, config.baseUrl())
                .header(HttpHeaders.REFERER, config.baseUrl() + "/")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchangeToMono(this::parseLoginResponse)
                .timeout(config.loginTimeout())
                .doOnError(
                        ex -> {
                            loginFailures.increment();
                            log.warn(
                                    "❌ JutSu login failed: {} (msg={})",
                                    ex.getClass().getSimpleName(),
                                    ex.getMessage());
                        });
    }

    private Mono<CachedSession> parseLoginResponse(ClientResponse response) {
        Map<String, String> cookies = collectSetCookies(response);
        String userId = cookies.get("dle_user_id");
        if (userId == null || userId.isBlank() || "0".equals(userId)) {
            loginFailures.increment();
            return Mono.error(
                    new IllegalStateException(
                            "JutSu login: response did not include dle_user_id cookie"
                                    + " (status="
                                    + response.statusCode()
                                    + ", cookies="
                                    + cookies.keySet()
                                    + ") — bad credentials or jut.su anti-bot challenge"));
        }
        return response.releaseBody()
                .thenReturn(
                        new CachedSession(
                                buildCookieHeader(cookies),
                                cookies.keySet().stream().sorted().toList(),
                                userId,
                                Instant.now(clock)));
    }

    private static Map<String, String> collectSetCookies(ClientResponse response) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> setCookies = response.headers().header(HttpHeaders.SET_COOKIE);
        for (String header : setCookies) {
            int eq = header.indexOf('=');
            if (eq <= 0) continue;
            String name = header.substring(0, eq).trim();
            int sep = header.indexOf(';', eq);
            String value = sep < 0 ? header.substring(eq + 1) : header.substring(eq + 1, sep);
            out.put(name, value.trim());
        }
        return out;
    }

    private static String buildCookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Test seam: build an absolute jut.su URL or pass through if already absolute. Mirrors the
     * loose path handling consumers expect (e.g. {@code /naruto/episode-1.html}).
     */
    public String absolutize(String href) {
        URI uri = URI.create(href);
        if (uri.isAbsolute()) {
            return href;
        }
        String base = config.baseUrl();
        if (!base.endsWith("/") && !href.startsWith("/")) {
            return base + "/" + href;
        }
        if (base.endsWith("/") && href.startsWith("/")) {
            return base.substring(0, base.length() - 1) + href;
        }
        return base + href;
    }

    /** Visible for tests; computes the form-encoded body shape we POST during login. */
    String previewLoginBody() {
        return "login_name="
                + urlEncode(config.username())
                + "&login_password="
                + urlEncode(config.password())
                + "&login=submit";
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** Test-visible accessor; exposes the currently cached session, if any. */
    @Nullable
    CachedSession peek() {
        return cached.get();
    }

    /**
     * True when credentials are configured. Used by the decoder to decide whether seeing {@code
     * JUTSU_PREMIUM_REQUIRED} is worth a "stale session" retry: without credentials we have nothing
     * to log into, so a retry is wasted bandwidth.
     */
    public boolean peekHasCredentials() {
        return config.hasCredentials();
    }

    /** Immutable snapshot of one login result. */
    static final class CachedSession {
        final String cookieHeader;
        final List<String> cookieNames;
        final String dleUserId;
        final Instant loggedInAt;

        CachedSession(
                String cookieHeader, List<String> cookieNames, String dleUserId, Instant when) {
            this.cookieHeader = cookieHeader;
            this.cookieNames = cookieNames;
            this.dleUserId = dleUserId;
            this.loggedInAt = when;
        }

        boolean expired(Clock clock, Duration ttl) {
            return Duration.between(loggedInAt, Instant.now(clock)).compareTo(ttl) >= 0;
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "CachedSession{user=%s,names=%s,age=%ds}",
                    dleUserId,
                    cookieNames,
                    Duration.between(loggedInAt, Instant.now()).toSeconds());
        }
    }
}
