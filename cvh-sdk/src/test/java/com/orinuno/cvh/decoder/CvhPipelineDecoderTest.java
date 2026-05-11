package com.orinuno.cvh.decoder;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.CvhErrorCodes;
import com.orinuno.cvh.api.CvhApiClient;
import com.orinuno.cvh.api.CvhVideoSourcesCache;
import com.orinuno.cvh.host.CvhHostPageParser;
import com.orinuno.cvh.host.CvhHostRegistry;
import com.orinuno.cvh.model.AnimeContent;
import com.orinuno.cvh.model.CvhVideoSources;
import com.orinuno.cvh.model.CvhVoiceTrack;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CvhPipelineDecoderTest {

    private static final String TITLE_BODY =
            """
{"items":[{"cvhId":"abc","vkId":"vk1","voiceStudio":"AniStar","voiceType":"Многоголосый"}]}
""";

    private static final String VIDEO_BODY =
            """
            {"unitedVideoId":1,"duration":10,"sources":{
              "hlsUrl":"https://ok/m.m3u8?expires=1810000000000&sig=z"
            }}
            """;

    private static CvhHostPageParser jutsuLike(AnimeContent content) {
        return new CvhHostPageParser() {
            @Override
            public String hostId() {
                return "stub";
            }

            @Override
            public boolean supports(URI pageUrl) {
                return pageUrl != null
                        && pageUrl.getHost() != null
                        && pageUrl.getHost().endsWith("jut-su.works");
            }

            @Override
            public AnimeContent parse(String html, String pageUrl) {
                return content;
            }
        };
    }

    private static AnimeContent withCvh() {
        return new AnimeContent(
                "slug",
                "https://jut-su.works/slug",
                "Title",
                "Original",
                "Desc",
                List.of("Экшен"),
                "2026",
                "Япония",
                "https://poster",
                null,
                "61192",
                "910",
                "mali",
                null,
                null);
    }

    private static AnimeContent withoutCvh() {
        return new AnimeContent(
                "slug",
                "https://jut-su.works/slug",
                "Title",
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static WebClient.Builder cannedResponses(
            String html, String titleBody, String videoBody) {
        return WebClient.builder()
                .exchangeFunction(
                        req -> {
                            String url = req.url().toString();
                            if (url.contains("/api/v1/player/sv/video/")) {
                                return Mono.just(jsonResponse(videoBody));
                            }
                            if (url.contains("/api/v1/player/")) {
                                return Mono.just(jsonResponse(titleBody));
                            }
                            return Mono.just(htmlResponse(html));
                        });
    }

    private static ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    private static ClientResponse htmlResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.TEXT_HTML_VALUE)
                .body(body)
                .build();
    }

    private CvhPipelineDecoder buildDecoder(
            CvhHostPageParser host, WebClient.Builder webClientBuilder) {
        CvhConfig config = CvhConfig.builder().build();
        CvhApiClient apiClient = new CvhApiClient(config, webClientBuilder);
        CvhVideoSourcesCache cache = new CvhVideoSourcesCache(apiClient, config);
        CvhHostRegistry registry = new CvhHostRegistry(List.of(host));
        return new CvhPipelineDecoder(config, registry, apiClient, cache, webClientBuilder);
    }

    @Test
    void fullPipelineEndToEnd() {
        WebClient.Builder builder = cannedResponses("<html/>", TITLE_BODY, VIDEO_BODY);
        CvhPipelineDecoder decoder = buildDecoder(jutsuLike(withCvh()), builder);

        StepVerifier.create(decoder.decode("https://jut-su.works/slug"))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isTrue();
                            assertThat(r.value().metadata().title()).isEqualTo("Title");
                            assertThat(r.value().tracks()).hasSize(1);
                            CvhVideoSources s = r.value().tracks().get(0).sources();
                            assertThat(s.hlsUrl()).startsWith("https://ok/m.m3u8");
                            assertThat(s.expiresAt())
                                    .isEqualTo(Instant.ofEpochMilli(1810000000000L));
                            CvhVoiceTrack t = r.value().tracks().get(0).track();
                            assertThat(t.voiceStudio()).isEqualTo("AniStar");
                        })
                .verifyComplete();
    }

    @Test
    void unsupportedHostShortCircuits() {
        WebClient.Builder builder = cannedResponses("<html/>", TITLE_BODY, VIDEO_BODY);
        CvhPipelineDecoder decoder = buildDecoder(jutsuLike(withCvh()), builder);

        StepVerifier.create(decoder.decode("https://other.test/x"))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isFalse();
                            assertThat(r.errorCode()).isEqualTo(CvhErrorCodes.CVH_UNSUPPORTED_HOST);
                        })
                .verifyComplete();
    }

    @Test
    void pageWithoutCvhPlayerReturnsEmptyTracks() {
        WebClient.Builder builder = cannedResponses("<html/>", TITLE_BODY, VIDEO_BODY);
        CvhPipelineDecoder decoder = buildDecoder(jutsuLike(withoutCvh()), builder);

        StepVerifier.create(decoder.decode("https://jut-su.works/slug"))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isTrue();
                            assertThat(r.value().tracks()).isEmpty();
                            assertThat(r.value().metadata().title()).isEqualTo("Title");
                        })
                .verifyComplete();
    }

    @Test
    void derivesRefererFromPageOrigin() {
        assertThat(CvhPipelineDecoder.deriveReferer("https://jut-su.works/all-you-need-is-kill"))
                .isEqualTo("https://jut-su.works/");
        assertThat(CvhPipelineDecoder.deriveReferer("https://other.test/slug?utm=1"))
                .isEqualTo("https://other.test/");
        assertThat(CvhPipelineDecoder.deriveReferer("")).isEmpty();
        assertThat(CvhPipelineDecoder.deriveReferer("not a url")).isEmpty();
        assertThat(CvhPipelineDecoder.deriveReferer(null)).isEmpty();
    }

    @Test
    void deriveRefererRejectsHostsWithControlChars() {
        // Header injection guard — any URL whose host smuggles CR/LF/TAB must yield empty.
        assertThat(CvhPipelineDecoder.deriveReferer("https://evil.com\r\nX-Inj: y/foo")).isEmpty();
        assertThat(CvhPipelineDecoder.deriveReferer("https://evil.com\tfoo/bar")).isEmpty();
    }

    @Test
    void sanitizeForLogReplacesControlChars() {
        assertThat(CvhPipelineDecoder.sanitizeForLog("https://x\r\nFAKE LINE"))
                .isEqualTo("https://x__FAKE LINE");
        assertThat(CvhPipelineDecoder.sanitizeForLog(null)).isEmpty();
        assertThat(CvhPipelineDecoder.sanitizeForLog("plain")).isEqualTo("plain");
    }

    @Test
    void redirectAllowedOnlyForRegisteredHostsOrRelative() {
        com.orinuno.cvh.host.CvhHostRegistry registry =
                new com.orinuno.cvh.host.CvhHostRegistry(List.of(jutsuLike(withCvh())));
        assertThat(CvhPipelineDecoder.isRedirectAllowed("/some-relative-slug", registry)).isTrue();
        assertThat(CvhPipelineDecoder.isRedirectAllowed("https://jut-su.works/x", registry))
                .isTrue();
        // Off-registry — typical SSRF target via 302.
        assertThat(CvhPipelineDecoder.isRedirectAllowed("http://169.254.169.254/", registry))
                .isFalse();
        assertThat(CvhPipelineDecoder.isRedirectAllowed("http://127.0.0.1/admin", registry))
                .isFalse();
        assertThat(CvhPipelineDecoder.isRedirectAllowed("https://evil.test/x", registry)).isFalse();
        // Bogus schemes.
        assertThat(CvhPipelineDecoder.isRedirectAllowed("file:///etc/passwd", registry)).isFalse();
        assertThat(CvhPipelineDecoder.isRedirectAllowed("gopher://x/", registry)).isFalse();
        assertThat(CvhPipelineDecoder.isRedirectAllowed(null, registry)).isFalse();
        assertThat(CvhPipelineDecoder.isRedirectAllowed("", registry)).isFalse();
    }

    @Test
    void refererForwardedToPlapi() {
        java.util.List<String> capturedReferers = new java.util.ArrayList<>();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req -> {
                                    String url = req.url().toString();
                                    if (url.contains("plapi") || url.contains("cdnvideohub")) {
                                        capturedReferers.add(req.headers().getFirst("Referer"));
                                    }
                                    if (url.contains("/api/v1/player/sv/video/")) {
                                        return Mono.just(jsonResponse(VIDEO_BODY));
                                    }
                                    if (url.contains("/api/v1/player/")) {
                                        return Mono.just(jsonResponse(TITLE_BODY));
                                    }
                                    return Mono.just(htmlResponse("<html/>"));
                                });
        CvhPipelineDecoder decoder = buildDecoder(jutsuLike(withCvh()), builder);
        decoder.decode("https://jut-su.works/slug").block();
        assertThat(capturedReferers).isNotEmpty().allMatch("https://jut-su.works/"::equals);
    }

    @Test
    void pageFetch500MapsToFetchError() {
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req -> {
                                    if (req.url().toString().contains("plapi")) {
                                        return Mono.just(jsonResponse(TITLE_BODY));
                                    }
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                                                    .body("nginx 500")
                                                    .build());
                                });
        CvhPipelineDecoder decoder = buildDecoder(jutsuLike(withCvh()), builder);
        StepVerifier.create(decoder.decode("https://jut-su.works/slug"))
                .assertNext(r -> assertThat(r.errorCode()).isEqualTo(CvhErrorCodes.CVH_FETCH_ERROR))
                .verifyComplete();
    }

    @Test
    void apiCallCountedOncePerVkIdWithinTtl() {
        AtomicInteger plapiVideoCalls = new AtomicInteger();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req -> {
                                    String url = req.url().toString();
                                    if (url.contains("/api/v1/player/sv/video/")) {
                                        plapiVideoCalls.incrementAndGet();
                                        return Mono.just(jsonResponse(VIDEO_BODY));
                                    }
                                    if (url.contains("/api/v1/player/")) {
                                        return Mono.just(jsonResponse(TITLE_BODY));
                                    }
                                    return Mono.just(htmlResponse("<html/>"));
                                });
        CvhPipelineDecoder decoder = buildDecoder(jutsuLike(withCvh()), builder);
        decoder.decode("https://jut-su.works/slug").block();
        decoder.decode("https://jut-su.works/slug").block();
        assertThat(plapiVideoCalls.get()).isEqualTo(1);
    }
}
