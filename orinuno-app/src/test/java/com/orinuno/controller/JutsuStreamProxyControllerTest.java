package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.kodik.client.http.RotatingUserAgentProvider;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

class JutsuStreamProxyControllerTest {

    /**
     * Tiny exchange function that returns a canned response and records the request. Mirrors the
     * pattern used in {@code JutsuSessionManagerTest} so the testing surface stays consistent.
     */
    static final class StubExchange implements ExchangeFunction {
        final List<ClientRequest> requests = new ArrayList<>();
        final List<ClientResponse> responses;
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

    private OrinunoProperties props;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        props = new OrinunoProperties();
        // Decoder rate limit is shared with the proxy. Set high so back-to-back tests aren't
        // serialised by the 1 RPS budget; an explicit test asserts the wiring works.
        props.getProviders().getJutsu().setRateLimitRps(100.0);
        registry = new SimpleMeterRegistry();
    }

    private JutsuStreamProxyController buildController(StubExchange stub) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(stub);
        // Build the SDK config from OrinunoProperties exactly the way JutsuSdkConfiguration
        // does, so the test exercises the same wiring path production uses.
        OrinunoProperties.JutsuProperties jp = props.getProviders().getJutsu();
        JutsuConfig config =
                JutsuConfig.builder()
                        .baseUrl(jp.getBaseUrl())
                        .credentials(jp.getUsername(), jp.getPassword())
                        .userAgent(new RotatingUserAgentProvider().stableDesktop())
                        .rateLimitRps(jp.getRateLimitRps())
                        .build();
        JutsuRateLimiter rateLimiter = new JutsuRateLimiter(config::rateLimitRps, registry);
        JutsuSessionManager sessionManager =
                new JutsuSessionManager(config, rateLimiter, builder, registry);
        return new JutsuStreamProxyController(
                rateLimiter, sessionManager, new RotatingUserAgentProvider(), builder, registry);
    }

    private WebTestClient bind(JutsuStreamProxyController controller) {
        return WebTestClient.bindToController(controller).configureClient().build();
    }

    /**
     * Build a {@code /api/v1/sources/jutsu/stream?url=...} URI through Spring's {@link UriBuilder}
     * so the framework percent-encodes the upstream URL the same way a real browser would.
     * Hand-rolled {@code "%3A%2F%2F"} concatenation tripped over WebTestClient's URI template
     * handling and produced misleading 403s on unrelated assertions. The {@code path} parameter
     * lets a single test exercise either the canonical or the deprecated alias.
     */
    private static java.util.function.Function<UriBuilder, URI> streamUri(
            String path, String upstreamUrl) {
        return uriBuilder -> uriBuilder.path(path).queryParam("url", upstreamUrl).build();
    }

    /** Default helper — exercises the canonical path. */
    private static java.util.function.Function<UriBuilder, URI> streamUri(String upstreamUrl) {
        return streamUri("/api/v1/sources/jutsu/stream", upstreamUrl);
    }

    @Test
    void whitelistRejectsNonYandexHost() {
        StubExchange stub = new StubExchange(List.of());
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://evil.example.com/x.mp4"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(String.class)
                .isEqualTo("host not whitelisted");
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void allowedHostIsForwardedAndStatusPropagated() {
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .header(HttpHeaders.CONTENT_LENGTH, "1024")
                        .header(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/1000000")
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .body("body-bytes-here")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://r500501.yandexwebcache.org/a/1.mp4"))
                .header(HttpHeaders.RANGE, "bytes=0-1023")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.PARTIAL_CONTENT)
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_TYPE, "video/mp4")
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_RANGE, "bytes 0-1023/1000000")
                .expectHeader()
                .valueEquals(HttpHeaders.ACCEPT_RANGES, "bytes");

        assertThat(stub.requests).hasSize(1);
        ClientRequest sent = stub.requests.get(0);
        assertThat(sent.url()).isEqualTo(URI.create("https://r500501.yandexwebcache.org/a/1.mp4"));
        assertThat(sent.headers().getFirst(HttpHeaders.RANGE)).isEqualTo("bytes=0-1023");
        assertThat(sent.headers().getFirst(HttpHeaders.REFERER)).isEqualTo("https://jut.su/");
    }

    @Test
    void requestWithoutRangeHeaderForwardsNoRange() {
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .body("full body")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://r1.yandexwebcache.org/a"))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(stub.requests.get(0).headers().getFirst(HttpHeaders.RANGE)).isNull();
    }

    @Test
    void upstream403IsPropagatedNotMaskedAs500() {
        // If the user got a banned/expired URL, surface 403 verbatim so operator triage is
        // meaningful — never collapse upstream errors into an opaque 500.
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.CONTENT_TYPE, "text/html")
                        .body("<html>403</html>")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://r1.yandexwebcache.org/a"))
                .exchange()
                .expectStatus()
                .isForbidden()
                // Spring augments text/html with charset=UTF-8 by default; match prefix.
                .expectHeader()
                .valueMatches(HttpHeaders.CONTENT_TYPE, "text/html.*");
    }

    @Test
    void hostWhitelistRejectsLookalikeDomains() {
        // ".yandexwebcache.org" suffix match must not match deceptive subdomains like
        // "yandexwebcache.org.evil.com".
        StubExchange stub = new StubExchange(List.of());
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://yandexwebcache.org.evil.com/a.mp4"))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void hostWhitelistAcceptsAnyYandexWebcacheSubdomain() {
        // We've seen r420501, r500501, r270106, r380101 in production within minutes — accept
        // every numeric edge label, not just a known set.
        for (String host : List.of("r1.yandexwebcache.org", "r999999.yandexwebcache.org")) {
            URI uri = URI.create("https://" + host + "/x.mp4");
            assertThat(JutsuStreamProxyController.isAllowedHost(uri))
                    .as("host %s should be allowed", host)
                    .isTrue();
        }
    }

    @Test
    void hostWhitelistIsCaseInsensitive() {
        URI uri = URI.create("https://R270106.YandexWebcache.ORG/x.mp4");
        assertThat(JutsuStreamProxyController.isAllowedHost(uri)).isTrue();
    }

    @Test
    void onlyWhitelistedResponseHeadersAreForwarded() {
        // Hostile / sensitive headers from upstream must not leak through. Set-Cookie is the
        // canary — under no circumstances do we want CDN-side cookies reaching the browser.
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .header(HttpHeaders.SET_COOKIE, "session=evil; HttpOnly")
                        .header("X-Internal-Trace", "should-not-leak")
                        .body("body")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://r1.yandexwebcache.org/a"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(HttpHeaders.SET_COOKIE)
                .expectHeader()
                .doesNotExist("X-Internal-Trace")
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_TYPE, "video/mp4");
    }

    @Test
    void filenameQueryParamSetsContentDisposition() {
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .header(HttpHeaders.CONTENT_LENGTH, "1024")
                        .body("body")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/v1/sources/jutsu/stream")
                                        .queryParam("url", "https://r1.yandexwebcache.org/a.mp4")
                                        .queryParam("filename", "one-punch-man-s01e11-1080p.mp4")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueMatches(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"one-punch-man-s01e11-1080p\\.mp4\";.*");
    }

    @Test
    void noFilenameParamMeansNoContentDisposition() {
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .body("body")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://r1.yandexwebcache.org/a"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .doesNotExist(HttpHeaders.CONTENT_DISPOSITION);
    }

    @Test
    void contentDispositionStripsCRLFAndPathSeparators() {
        // CRLF MUST NEVER survive in the value — that's the actual header-injection vector. The
        // text "X-Injected:" inside a quoted-string portion is harmless (RFC 7230 quoted-string
        // is not re-parsed for headers), so we don't try to scrub the literal text. Path
        // separators get _ to avoid weird "Save as ../../etc/passwd" prompts on broken browsers.
        String injected = "evil\r\nX-Injected: yes\r\nfoo/bar\\baz\"q.mp4";
        String header = JutsuStreamProxyController.contentDisposition(injected);
        assertThat(header).doesNotContain("\r").doesNotContain("\n");
        assertThat(header).contains("foo_bar_baz");
        // The double-quote in the input must be backslash-escaped per RFC 6266 § 4.1 so the
        // browser parser doesn't see the value as terminated early.
        assertThat(header).contains("\\\"q.mp4");
    }

    @Test
    void contentDispositionEncodesUnicodeFilename() {
        // Cyrillic episode title — must end up in the filename* RFC 5987 variant so the browser
        // shows it correctly. ASCII fallback is still present for ancient clients.
        String header =
                JutsuStreamProxyController.contentDisposition("Ванпанчмен — серия 11 (1080p).mp4");
        assertThat(header).startsWith("attachment; filename=\"");
        assertThat(header).contains("filename*=UTF-8''");
        // Encoded "Ванпанчмен" in UTF-8 percent-encoding starts with %D0%92...
        assertThat(header).contains("%D0%92");
    }

    @Test
    void deprecatedLegacyPathStillStreamsTheSameWayAsCanonical() {
        // Step 1 of the API split keeps /api/v1/providers/jutsu/stream working as a deprecation
        // alias so existing frontends don't break in-flight. Both paths must hit the same handler.
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .body("body")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("/api/v1/providers/jutsu/stream", "https://r1.yandexwebcache.org/a"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_TYPE, "video/mp4");
        assertThat(stub.requests).hasSize(1);
        assertThat(stub.requests.get(0).url())
                .isEqualTo(URI.create("https://r1.yandexwebcache.org/a"));
    }

    @Test
    void streamRequestUsesGetMethod() {
        ClientResponse upstream =
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                        .body("ok")
                        .build();
        StubExchange stub = new StubExchange(List.of(upstream));
        JutsuStreamProxyController controller = buildController(stub);
        bind(controller)
                .get()
                .uri(streamUri("https://r1.yandexwebcache.org/a"))
                .accept(MediaType.ALL)
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(stub.requests.get(0).method().name()).isEqualTo("GET");
    }
}
