package com.orinuno.aksor.decoder;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.aksor.AksorConfig;
import com.orinuno.aksor.AksorErrorCodes;
import com.orinuno.aksor.api.AksorApiClient;
import com.orinuno.aksor.host.AksorHostPageParser;
import com.orinuno.aksor.host.AksorHostRegistry;
import com.orinuno.aksor.model.AksorAnime;
import com.orinuno.aksor.model.AksorEpisode;
import com.orinuno.aksor.model.AksorVideoQualities;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AksorPipelineDecoderTest {

    private static AksorHostPageParser stubHost(AksorAnime anime, String supportsSuffix) {
        return new AksorHostPageParser() {
            @Override
            public String hostId() {
                return "stub";
            }

            @Override
            public boolean supports(URI pageUrl) {
                return pageUrl != null
                        && pageUrl.getHost() != null
                        && pageUrl.getHost().endsWith(supportsSuffix);
            }

            @Override
            public Mono<AksorAnime> resolve(String pageUrl) {
                return Mono.just(anime);
            }
        };
    }

    private static AksorPipelineDecoder buildDecoder(
            AksorHostPageParser host, String qualitiesBody) {
        AksorConfig config = AksorConfig.builder().build();
        WebClient.Builder webClient =
                WebClient.builder()
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(HttpStatus.OK)
                                                        .header(
                                                                "Content-Type",
                                                                MediaType.APPLICATION_JSON_VALUE)
                                                        .body(qualitiesBody)
                                                        .build()));
        AksorApiClient apiClient = new AksorApiClient(config, webClient);
        AksorHostRegistry registry = new AksorHostRegistry(List.of(host));
        return new AksorPipelineDecoder(config, registry, apiClient);
    }

    private static AksorAnime sampleAnime() {
        AksorEpisode ep =
                new AksorEpisode(
                        1L,
                        "1",
                        "Озвучка AniLibria",
                        "Плеер Aksor",
                        "248a4ad8181c6e5741371525d70e446b",
                        "https://player.aksor.tv/video/248a4ad8181c6e5741371525d70e446b",
                        1370,
                        null,
                        null,
                        null);
        return new AksorAnime(
                "10531",
                "monolog-farmatsevta",
                "https://old.yummyani.me/x",
                "Монолог",
                "https://p",
                List.of(ep));
    }

    @Test
    void enrichesEpisodesWithQualities() {
        String body =
                "{\"qualities\":{\"q1080\":\"https://cdn.aksor.tv/1.mpd\",\"q720\":null,"
                        + "\"q480\":null,\"q360\":null,\"q2k\":null,\"q4k\":null}}";
        AksorPipelineDecoder decoder = buildDecoder(stubHost(sampleAnime(), "yummyani.me"), body);

        StepVerifier.create(decoder.decode("https://old.yummyani.me/catalog/item/x"))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isTrue();
                            AksorVideoQualities q = r.value().episodes().get(0).qualities();
                            assertThat(q).isNotNull();
                            assertThat(q.bestAvailable()).isEqualTo("https://cdn.aksor.tv/1.mpd");
                        })
                .verifyComplete();
    }

    @Test
    void unsupportedHostShortCircuits() {
        AksorPipelineDecoder decoder = buildDecoder(stubHost(sampleAnime(), "yummyani.me"), "{}");
        StepVerifier.create(decoder.decode("https://other.test/x"))
                .assertNext(
                        r ->
                                assertThat(r.errorCode())
                                        .isEqualTo(AksorErrorCodes.AKSOR_UNSUPPORTED_HOST))
                .verifyComplete();
    }

    @Test
    void derivesRefererFromPageOrigin() {
        assertThat(AksorPipelineDecoder.deriveReferer("https://old.yummyani.me/catalog/item/x"))
                .isEqualTo("https://old.yummyani.me/");
        assertThat(AksorPipelineDecoder.deriveReferer(null)).isEmpty();
        assertThat(AksorPipelineDecoder.deriveReferer("https://evil\r\n.test/x")).isEmpty();
    }

    @Test
    void sanitizeForLogScrubsControls() {
        assertThat(AksorPipelineDecoder.sanitizeForLog("a\r\nb")).isEqualTo("a__b");
        assertThat(AksorPipelineDecoder.sanitizeForLog(null)).isEmpty();
    }

    // -------- episode filter -------------------------------------------------

    private static AksorAnime multiEpisodeAnime() {
        AksorEpisode ep1 =
                new AksorEpisode(
                        1L,
                        "1",
                        "Озвучка AniLibria",
                        "Плеер Aksor",
                        "11111111111111111111111111111111",
                        "https://player.aksor.tv/video/11111111111111111111111111111111",
                        1370,
                        null,
                        null,
                        null);
        AksorEpisode ep2 =
                new AksorEpisode(
                        2L,
                        "2",
                        "Озвучка AniStar",
                        "Плеер Aksor",
                        "22222222222222222222222222222222",
                        "https://player.aksor.tv/video/22222222222222222222222222222222",
                        1370,
                        null,
                        null,
                        null);
        AksorEpisode ep3 =
                new AksorEpisode(
                        3L,
                        "3",
                        "Озвучка AniLibria",
                        "Плеер Aksor",
                        "33333333333333333333333333333333",
                        "https://player.aksor.tv/video/33333333333333333333333333333333",
                        1370,
                        null,
                        null,
                        null);
        return new AksorAnime(
                "10531",
                "x",
                "https://old.yummyani.me/x",
                "X",
                "https://p",
                List.of(ep1, ep2, ep3));
    }

    private static AksorPipelineDecoder counterDecoder(
            AksorHostPageParser host,
            String qualitiesBody,
            java.util.concurrent.atomic.AtomicInteger counter) {
        AksorConfig config = AksorConfig.builder().build();
        WebClient.Builder webClient =
                WebClient.builder()
                        .exchangeFunction(
                                req -> {
                                    counter.incrementAndGet();
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            "Content-Type",
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(qualitiesBody)
                                                    .build());
                                });
        AksorApiClient apiClient = new AksorApiClient(config, webClient);
        AksorHostRegistry registry = new AksorHostRegistry(List.of(host));
        return new AksorPipelineDecoder(config, registry, apiClient);
    }

    @Test
    void filterByNumberSkipsOtherEpisodeApiCalls() {
        String body =
                "{\"qualities\":{\"q1080\":\"https://cdn.aksor.tv/x.mpd\",\"q720\":null,"
                        + "\"q480\":null,\"q360\":null,\"q2k\":null,\"q4k\":null}}";
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        AksorPipelineDecoder decoder =
                counterDecoder(stubHost(multiEpisodeAnime(), "yummyani.me"), body, calls);

        StepVerifier.create(
                        decoder.decode(
                                "https://old.yummyani.me/x",
                                com.orinuno.aksor.AksorEpisodeFilter.byNumber("2")))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isTrue();
                            assertThat(r.value().episodes()).hasSize(1);
                            assertThat(r.value().episodes().get(0).number()).isEqualTo("2");
                        })
                .verifyComplete();
        assertThat(calls.get())
                .as("only one player.aksor.tv API call for the matched episode")
                .isEqualTo(1);
    }

    @Test
    void filterByDubbingKeepsAllMatching() {
        String body =
                "{\"qualities\":{\"q1080\":\"https://cdn.aksor.tv/x.mpd\",\"q720\":null,"
                        + "\"q480\":null,\"q360\":null,\"q2k\":null,\"q4k\":null}}";
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        AksorPipelineDecoder decoder =
                counterDecoder(stubHost(multiEpisodeAnime(), "yummyani.me"), body, calls);

        StepVerifier.create(
                        decoder.decode(
                                "https://old.yummyani.me/x",
                                com.orinuno.aksor.AksorEpisodeFilter.byDubbing("AniLibria")))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isTrue();
                            assertThat(r.value().episodes()).hasSize(2);
                            assertThat(r.value().episodes())
                                    .extracting(AksorEpisode::number)
                                    .containsExactly("1", "3");
                        })
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void filterMatchingNothingYieldsNoEpisodesMatched() {
        AksorPipelineDecoder decoder =
                buildDecoder(stubHost(multiEpisodeAnime(), "yummyani.me"), "{}");

        StepVerifier.create(
                        decoder.decode(
                                "https://old.yummyani.me/x",
                                com.orinuno.aksor.AksorEpisodeFilter.byNumber("999")))
                .assertNext(
                        r -> {
                            assertThat(r.success()).isFalse();
                            assertThat(r.errorCode())
                                    .isEqualTo(AksorErrorCodes.AKSOR_NO_EPISODES_MATCHED);
                        })
                .verifyComplete();
    }

    @Test
    void nullFilterFallsBackToAll() {
        String body =
                "{\"qualities\":{\"q1080\":\"https://cdn.aksor.tv/x.mpd\",\"q720\":null,"
                        + "\"q480\":null,\"q360\":null,\"q2k\":null,\"q4k\":null}}";
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        AksorPipelineDecoder decoder =
                counterDecoder(stubHost(multiEpisodeAnime(), "yummyani.me"), body, calls);

        StepVerifier.create(decoder.decode("https://old.yummyani.me/x", null))
                .assertNext(r -> assertThat(r.value().episodes()).hasSize(3))
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(3);
    }
}
