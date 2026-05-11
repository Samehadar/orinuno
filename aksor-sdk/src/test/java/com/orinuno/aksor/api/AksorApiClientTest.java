package com.orinuno.aksor.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.aksor.AksorConfig;
import com.orinuno.aksor.AksorErrorCodes;
import com.orinuno.aksor.AksorException;
import com.orinuno.aksor.model.AksorVideoQualities;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AksorApiClientTest {

    private static final String QUALITIES_BODY =
            """
            {
              "id": "248a4ad8181c6e5741371525d70e446b",
              "anime_id": 10531,
              "episode": "1",
              "studio_name": "AniLibria",
              "qualities": {
                "q1080": "https://cdn.aksor.tv/path/1080.mpd",
                "q720": null,
                "q480": null,
                "q360": null,
                "q2k": null,
                "q4k": null
              }
            }
            """;

    private AksorApiClient build(String body, AtomicReference<ClientRequest> captured) {
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
        return new AksorApiClient(AksorConfig.builder().build(), builder);
    }

    @Test
    void getQualitiesMapsBody() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        AksorApiClient api = build(QUALITIES_BODY, captured);
        AksorVideoQualities q =
                api.getQualities("248a4ad8181c6e5741371525d70e446b", "https://old.yummyani.me/")
                        .block();
        assertThat(q).isNotNull();
        assertThat(q.q1080()).isEqualTo("https://cdn.aksor.tv/path/1080.mpd");
        assertThat(q.q720()).isNull();
        assertThat(q.bestAvailable()).isEqualTo("https://cdn.aksor.tv/path/1080.mpd");
        ClientRequest req = captured.get();
        assertThat(req.url().toString()).endsWith("/api/video/248a4ad8181c6e5741371525d70e446b");
        assertThat(req.headers().getFirst("Referer")).isEqualTo("https://old.yummyani.me/");
        assertThat(req.headers().getFirst("Origin")).isEqualTo("https://player.aksor.tv");
    }

    @Test
    void getQualitiesFallsBackToConfigRefererWhenNullOverride() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        AksorApiClient api = build(QUALITIES_BODY, captured);
        api.getQualities("248a4ad8181c6e5741371525d70e446b").block();
        assertThat(captured.get().headers().getFirst("Referer"))
                .isEqualTo("https://player.aksor.tv/");
    }

    @Test
    void rejectsInvalidHash() {
        AksorApiClient api = build("{}", new AtomicReference<>());
        StepVerifier.create(api.getQualities("not-a-hash"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((AksorException) ex).errorCode())
                                        .isEqualTo(AksorErrorCodes.AKSOR_API_ERROR))
                .verify();
    }

    @Test
    void allBlankQualitiesYieldsNoQualities() {
        AksorApiClient api =
                build(
                        "{\"qualities\":{\"q1080\":null,\"q720\":null,\"q480\":null,"
                                + "\"q360\":null,\"q2k\":null,\"q4k\":null}}",
                        new AtomicReference<>());
        StepVerifier.create(api.getQualities("248a4ad8181c6e5741371525d70e446b"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((AksorException) ex).errorCode())
                                        .isEqualTo(AksorErrorCodes.AKSOR_NO_QUALITIES))
                .verify();
    }

    @Test
    void upstream500MapsToApiError() {
        WebClient.Builder builder =
                WebClient.builder()
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(
                                                                HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body("nginx 500")
                                                        .build()));
        AksorApiClient api = new AksorApiClient(AksorConfig.builder().build(), builder);
        StepVerifier.create(api.getQualities("248a4ad8181c6e5741371525d70e446b"))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((AksorException) ex).errorCode())
                                        .isEqualTo(AksorErrorCodes.AKSOR_API_ERROR))
                .verify();
    }
}
