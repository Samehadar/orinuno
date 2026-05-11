package com.orinuno.cvh.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.CvhErrorCodes;
import com.orinuno.cvh.model.CvhVideoSources;
import com.orinuno.cvh.model.CvhVoiceTrack;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CvhApiClientTest {

    private static final String TITLE_BODY =
            """
{"titleName":"Test","isSerial":false,"items":[
  {"cvhId":"abc","vkId":"13183611132656","voiceStudio":"AniStar","voiceType":"Многоголосый"},
  {"cvhId":"def","vkId":"12000000000000","voiceStudio":"AnilibriaTV","voiceType":"Многоголосый"}
],"trailers":null}
""";

    private static final String VIDEO_BODY =
            """
{"unitedVideoId":13183611132656,"duration":5186,"failoverHost":"vd566.okcdn.ru",
 "thumbUrl":"https://iv.okcdn.ru/x",
 "sources":{
   "hlsUrl":"https://ok6-1.vkuser.net/m.m3u8?expires=1810000000000&sig=z&srcIp=1.1.1.1",
   "dashUrl":"https://ok6-1.vkuser.net/?expires=1810000000000&sig=z&type=1",
   "mpegFullHdUrl":"https://ok6-1.vkuser.net/?type=5",
   "mpegHighUrl":"https://ok6-1.vkuser.net/?type=3",
   "mpegMediumUrl":"https://ok6-1.vkuser.net/?type=2",
   "mpegLowUrl":"https://ok6-1.vkuser.net/?type=1",
   "mpegLowestUrl":"https://ok6-1.vkuser.net/?type=0",
   "mpegTinyUrl":"https://ok6-1.vkuser.net/?type=4",
   "mpegQhdUrl":"","mpeg2kUrl":"","mpeg4kUrl":""
 }
}
""";

    private CvhApiClient clientReturning(String body, AtomicReference<ClientRequest> captured) {
        CvhConfig config = CvhConfig.builder().build();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req -> {
                                    captured.set(req);
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            "Content-Type",
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(body)
                                                    .build());
                                });
        return new CvhApiClient(config, builder);
    }

    @Test
    void titleTracksMapped() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        CvhApiClient api = clientReturning(TITLE_BODY, captured);
        List<CvhVoiceTrack> tracks =
                api.getTitleVoiceTracks("61192", "910", "mali", "https://jut-su.works/").block();
        assertThat(tracks)
                .hasSize(2)
                .extracting(CvhVoiceTrack::vkId)
                .containsExactly("13183611132656", "12000000000000");
        ClientRequest req = captured.get();
        assertThat(req.url().toString())
                .contains("/api/v1/player/sv/playlist")
                .contains("id=61192")
                .contains("aggr=mali")
                .contains("pub=910");
        assertThat(req.headers().getFirst("Referer")).isEqualTo("https://jut-su.works/");
        assertThat(req.headers().getFirst("User-Agent")).contains("Chrome");
    }

    @Test
    void titleTracksFallBackToConfigRefererWhenOverrideAbsent() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        CvhApiClient api = clientReturning(TITLE_BODY, captured);
        api.getTitleVoiceTracks("1", "2", "mali").block();
        assertThat(captured.get().headers().getFirst("Referer"))
                .isEqualTo("https://player.cdnvideohub.com/");
    }

    @Test
    void emptyAggregatorFallsBackToConfigDefault() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        CvhApiClient api = clientReturning(TITLE_BODY, captured);
        api.getTitleVoiceTracks("1", "2", "").block();
        assertThat(captured.get().url().toString()).contains("aggr=mali");
    }

    @Test
    void videoSourcesMapped() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        CvhApiClient api = clientReturning(VIDEO_BODY, captured);
        CvhVideoSources s = api.getVideoSources("13183611132656", "https://jut-su.works/").block();
        assertThat(s).isNotNull();
        assertThat(s.vkId()).isEqualTo(13183611132656L);
        assertThat(s.durationSec()).isEqualTo(5186);
        assertThat(s.hlsUrl()).startsWith("https://ok6-1.vkuser.net/m.m3u8");
        assertThat(s.mp4_1080p()).isEqualTo("https://ok6-1.vkuser.net/?type=5");
        assertThat(s.mp4_144p()).isEqualTo("https://ok6-1.vkuser.net/?type=4");
        assertThat(s.expiresAt()).isNotNull();
        assertThat(s.expiresAt().toEpochMilli()).isEqualTo(1810000000000L);
        assertThat(captured.get().url().toString())
                .endsWith("/api/v1/player/sv/video/13183611132656");
        assertThat(captured.get().headers().getFirst("Referer")).isEqualTo("https://jut-su.works/");
    }

    @Test
    void emptyVkIdRejected() {
        CvhApiClient api = clientReturning("{}", new AtomicReference<>());
        StepVerifier.create(api.getVideoSources(""))
                .expectErrorSatisfies(
                        ex -> {
                            assertThat(ex).isInstanceOf(CvhApiException.class);
                            assertThat(((CvhApiException) ex).errorCode())
                                    .isEqualTo(CvhErrorCodes.CVH_VIDEO_NOT_FOUND);
                        })
                .verify();
    }

    @Test
    void missingSourcesNodeIsVideoNotFound() {
        CvhApiClient api = clientReturning("{\"unitedVideoId\":1}", new AtomicReference<>());
        StepVerifier.create(api.getVideoSources("1"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((CvhApiException) ex).errorCode())
                                        .isEqualTo(CvhErrorCodes.CVH_VIDEO_NOT_FOUND))
                .verify();
    }

    @Test
    void upstream500MapsToCvhApiError() {
        CvhConfig config = CvhConfig.builder().build();
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(
                                                                HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body("nginx 500")
                                                        .build()));
        CvhApiClient api = new CvhApiClient(config, builder);
        StepVerifier.create(api.getTitleVoiceTracks("1", "2", "mali"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((CvhApiException) ex).errorCode())
                                        .isEqualTo(CvhErrorCodes.CVH_API_ERROR))
                .verify();
    }
}
