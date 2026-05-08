package com.orinuno.jutsu.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class JutsuLiveFallbackServiceTest {

    @Mock private JutsuClient client;

    private JutsuRateLimiter rateLimiter;
    private JutsuFallbackCircuitBreaker breaker;
    private JutsuFallbackNegativeCache negativeCache;

    @BeforeEach
    void setUp() {
        // Fast bucket so the limiter never adds latency to the test.
        rateLimiter = new JutsuRateLimiter(() -> 1000.0, null);
        breaker =
                new JutsuFallbackCircuitBreaker(
                        4, 0.5, Duration.ofSeconds(60), Clock.systemUTC(), null);
        negativeCache = new JutsuFallbackNegativeCache(Duration.ofSeconds(30), 1000, null);
    }

    @Test
    @DisplayName("disabled service short-circuits with FallbackDisabledException")
    void disabledServiceRejects() {
        JutsuLiveFallbackService service =
                new JutsuLiveFallbackService(
                        client, rateLimiter, breaker, negativeCache, /* enabled= */ false, null);

        StepVerifier.create(service.liveAnimeInfo("naruto"))
                .expectError(JutsuLiveFallbackService.FallbackDisabledException.class)
                .verify();
        verify(client, never()).getAnimeInfo(any());
    }

    @Test
    @DisplayName(
            "happy path — live call succeeds; breaker records success; negative cache stays"
                    + " empty")
    void happyPathFlowsThrough() {
        JutsuLiveFallbackService service =
                new JutsuLiveFallbackService(
                        client, rateLimiter, breaker, negativeCache, true, null);
        JutsuAnimeInfo expected = animeInfo("naruto");
        when(client.getAnimeInfo("naruto")).thenReturn(Mono.just(expected));

        StepVerifier.create(service.liveAnimeInfo("naruto")).expectNext(expected).verifyComplete();
        verify(client, times(1)).getAnimeInfo("naruto");
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.CLOSED);
        assertThat(breaker.failureRate()).isZero();
        assertThat(negativeCache.size()).isZero();
    }

    @Test
    @DisplayName(
            "live failure marks negative cache; subsequent call short-circuits with"
                    + " NegativeCacheHitException without invoking SDK")
    void failureSeedsNegativeCache() {
        JutsuLiveFallbackService service =
                new JutsuLiveFallbackService(
                        client, rateLimiter, breaker, negativeCache, true, null);
        when(client.getAnimeInfo("ghost"))
                .thenReturn(Mono.error(new RuntimeException("404 from jut.su")));

        StepVerifier.create(service.liveAnimeInfo("ghost"))
                .expectErrorMessage("404 from jut.su")
                .verify();
        StepVerifier.create(service.liveAnimeInfo("ghost"))
                .expectError(JutsuLiveFallbackService.NegativeCacheHitException.class)
                .verify();
        // Despite two .liveAnimeInfo calls, the SDK was hit only once — the second was caught by
        // the negative cache marker.
        verify(client, times(1)).getAnimeInfo("ghost");
    }

    @Test
    @DisplayName(
            "circuit breaker opens after enough failures; further calls short-circuit with"
                    + " BreakerOpenException without invoking SDK")
    void breakerOpensAndShortCircuits() {
        JutsuLiveFallbackService service =
                new JutsuLiveFallbackService(
                        client, rateLimiter, breaker, negativeCache, true, null);
        // Distinct slugs so the negative cache doesn't catch the second/third/fourth call before
        // the breaker has a chance to see the failure.
        when(client.getAnimeInfo("a")).thenReturn(Mono.error(new RuntimeException("a")));
        when(client.getAnimeInfo("b")).thenReturn(Mono.error(new RuntimeException("b")));
        when(client.getAnimeInfo("c")).thenReturn(Mono.error(new RuntimeException("c")));
        when(client.getAnimeInfo("d")).thenReturn(Mono.error(new RuntimeException("d")));

        StepVerifier.create(service.liveAnimeInfo("a")).expectError().verify();
        StepVerifier.create(service.liveAnimeInfo("b")).expectError().verify();
        StepVerifier.create(service.liveAnimeInfo("c")).expectError().verify();
        StepVerifier.create(service.liveAnimeInfo("d")).expectError().verify();
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.OPEN);

        // 5th distinct call — must NOT hit the SDK; breaker short-circuits first.
        StepVerifier.create(service.liveAnimeInfo("e"))
                .expectError(JutsuLiveFallbackService.BreakerOpenException.class)
                .verify();
        verify(client, never()).getAnimeInfo("e");
    }

    @Test
    @DisplayName(
            "catalogKey is stable across separate JutsuCatalogRequest instances built with the"
                    + " same path / page / search")
    void catalogKeyIsStable() {
        JutsuCatalogRequest a = JutsuCatalogRequest.unfiltered(2);
        JutsuCatalogRequest b = JutsuCatalogRequest.unfiltered(2);
        JutsuCatalogRequest c = JutsuCatalogRequest.unfiltered(3);
        assertThat(JutsuLiveFallbackService.catalogKey(a))
                .isEqualTo(JutsuLiveFallbackService.catalogKey(b));
        assertThat(JutsuLiveFallbackService.catalogKey(a))
                .as(
                        "page-2 and page-3 of the same filter must hash to distinct keys so failing"
                                + " on page 2 doesn't poison page 3")
                .isNotEqualTo(JutsuLiveFallbackService.catalogKey(c));
    }

    @Test
    @DisplayName(
            "catalog request integration — live success flows through, failure populates the"
                    + " catalog-flavoured negative-cache key")
    void catalogIntegration() {
        JutsuLiveFallbackService service =
                new JutsuLiveFallbackService(
                        client, rateLimiter, breaker, negativeCache, true, null);
        JutsuCatalogRequest req = JutsuCatalogRequest.unfiltered(1);
        JutsuCatalogPage page = new JutsuCatalogPage(List.of(), 1, false);
        when(client.browseCatalog(req)).thenReturn(Mono.just(page));

        StepVerifier.create(service.liveBrowseCatalog(req)).expectNext(page).verifyComplete();
        assertThat(negativeCache.size())
                .as("successful catalog call must NOT seed the negative cache")
                .isZero();

        when(client.browseCatalog(req)).thenReturn(Mono.error(new RuntimeException("503")));
        StepVerifier.create(service.liveBrowseCatalog(req)).expectError().verify();
        assertThat(negativeCache.isMarked(JutsuLiveFallbackService.catalogKey(req)))
                .as("failed catalog call must be marked under the catalogKey")
                .isTrue();
    }

    private static JutsuAnimeInfo animeInfo(String slug) {
        return new JutsuAnimeInfo(
                slug,
                slug,
                slug,
                "synopsis",
                Optional.empty(),
                List.of(),
                Optional.empty(),
                java.util.Set.of(),
                java.util.Set.of(),
                "thumb",
                List.of());
    }
}
