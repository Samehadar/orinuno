package com.orinuno.cvh.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.model.CvhVideoSources;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class CvhVideoSourcesCacheTest {

    /**
     * Builds a real {@link CvhApiClient} backed by a canned exchange function. Each call dequeues
     * one response body and increments the call counter.
     */
    private static final class FakeBackend {
        final Deque<String> bodies = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();

        CvhApiClient client(CvhConfig config) {
            WebClient.Builder builder =
                    WebClient.builder()
                            .exchangeFunction(
                                    req -> {
                                        calls.incrementAndGet();
                                        String body = bodies.poll();
                                        if (body == null) {
                                            return Mono.just(
                                                    ClientResponse.create(
                                                                    HttpStatus
                                                                            .INTERNAL_SERVER_ERROR)
                                                            .body("queue empty")
                                                            .build());
                                        }
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
    }

    private static String videoBodyExpiringAt(Instant expiresAt) {
        return "{\"unitedVideoId\":1,\"duration\":100,\"sources\":{"
                + "\"hlsUrl\":\"https://ok/m.m3u8?expires="
                + expiresAt.toEpochMilli()
                + "&sig=z\"}}";
    }

    @Test
    void fetchOnlyOnceWhenWithinTtl() {
        CvhConfig cfg = CvhConfig.builder().tokenRefreshMarginMinutes(30).build();
        Instant now = Instant.parse("2026-05-11T10:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);

        FakeBackend backend = new FakeBackend();
        backend.bodies.add(videoBodyExpiringAt(now.plusSeconds(3600)));
        CvhApiClient api = backend.client(cfg);

        CvhVideoSourcesCache cache = new CvhVideoSourcesCache(api, cfg, fixed);
        CvhVideoSources first = cache.getOrFetch("vk1").block();
        CvhVideoSources second = cache.getOrFetch("vk1").block();
        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
        assertThat(backend.calls.get()).isEqualTo(1);
    }

    @Test
    void refetchesWhenWithinRefreshMargin() {
        CvhConfig cfg = CvhConfig.builder().tokenRefreshMarginMinutes(30).build();
        Instant now = Instant.parse("2026-05-11T10:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);

        FakeBackend backend = new FakeBackend();
        backend.bodies.add(videoBodyExpiringAt(now.plusSeconds(5 * 60)));
        backend.bodies.add(videoBodyExpiringAt(now.plusSeconds(86400)));
        CvhApiClient api = backend.client(cfg);

        CvhVideoSourcesCache cache = new CvhVideoSourcesCache(api, cfg, fixed);
        Instant firstExpiry = cache.getOrFetch("vk1").block().expiresAt();
        Instant secondExpiry = cache.getOrFetch("vk1").block().expiresAt();
        assertThat(firstExpiry).isEqualTo(now.plusSeconds(5 * 60));
        assertThat(secondExpiry).isEqualTo(now.plusSeconds(86400));
        assertThat(backend.calls.get()).isEqualTo(2);
    }

    @Test
    void invalidateClearsEntry() {
        CvhConfig cfg = CvhConfig.builder().build();
        FakeBackend backend = new FakeBackend();
        backend.bodies.add(videoBodyExpiringAt(Instant.now().plusSeconds(86400)));
        CvhApiClient api = backend.client(cfg);

        CvhVideoSourcesCache cache = new CvhVideoSourcesCache(api, cfg);
        cache.getOrFetch("vk1").block();
        assertThat(cache.size()).isEqualTo(1);
        cache.invalidate("vk1");
        assertThat(cache.size()).isZero();
    }
}
