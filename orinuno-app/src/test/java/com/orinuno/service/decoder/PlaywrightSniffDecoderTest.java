package com.orinuno.service.decoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.service.PlaywrightVideoFetcher;
import com.orinuno.service.PlaywrightVideoFetcher.InterceptedVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * DECODE-8 — covers the wiring around {@link PlaywrightVideoFetcher} so the orchestrator can always
 * rely on a non-erroring Mono with an empty-map fallback.
 */
class PlaywrightSniffDecoderTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    @DisplayName("isAvailable=false when no fetcher is wired")
    void unavailableWithoutFetcher() {
        PlaywrightSniffDecoder decoder = new PlaywrightSniffDecoder(providerOf(null));
        assertThat(decoder.isAvailable()).isFalse();
        StepVerifier.create(decoder.sniff("//k/1")).expectNext(java.util.Map.of()).verifyComplete();
    }

    @Test
    @DisplayName("isAvailable mirrors the fetcher state")
    void availabilityFollowsFetcher() {
        PlaywrightVideoFetcher fetcher = mock(PlaywrightVideoFetcher.class);
        when(fetcher.isAvailable()).thenReturn(false);
        PlaywrightSniffDecoder decoder = new PlaywrightSniffDecoder(providerOf(fetcher));
        assertThat(decoder.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("sniff returns empty map when fetcher is not available")
    void sniffEmptyWhenFetcherUnavailable() {
        PlaywrightVideoFetcher fetcher = mock(PlaywrightVideoFetcher.class);
        when(fetcher.isAvailable()).thenReturn(false);
        PlaywrightSniffDecoder decoder = new PlaywrightSniffDecoder(providerOf(fetcher));

        StepVerifier.create(decoder.sniff("//k/1")).expectNext(java.util.Map.of()).verifyComplete();
    }

    @Test
    @DisplayName("sniff propagates intercepted URL under SNIFF_QUALITY_KEY")
    void sniffPropagatesUrl() {
        PlaywrightVideoFetcher fetcher = mock(PlaywrightVideoFetcher.class);
        when(fetcher.isAvailable()).thenReturn(true);
        when(fetcher.interceptVideoUrl("//k/2"))
                .thenReturn(
                        Mono.just(
                                new InterceptedVideo(
                                        "https://cdn/master.m3u8", "application/x-mpegURL", 5000)));
        PlaywrightSniffDecoder decoder = new PlaywrightSniffDecoder(providerOf(fetcher));

        StepVerifier.create(decoder.sniff("//k/2"))
                .assertNext(
                        result ->
                                assertThat(result)
                                        .containsEntry(
                                                PlaywrightSniffDecoder.SNIFF_QUALITY_KEY,
                                                "https://cdn/master.m3u8"))
                .verifyComplete();
    }

    @Test
    @DisplayName("sniff swallows errors and returns empty map")
    void sniffSwallowsErrors() {
        PlaywrightVideoFetcher fetcher = mock(PlaywrightVideoFetcher.class);
        when(fetcher.isAvailable()).thenReturn(true);
        when(fetcher.interceptVideoUrl("//k/3"))
                .thenReturn(Mono.error(new RuntimeException("playwright timeout")));
        PlaywrightSniffDecoder decoder = new PlaywrightSniffDecoder(providerOf(fetcher));

        StepVerifier.create(decoder.sniff("//k/3")).expectNext(java.util.Map.of()).verifyComplete();
    }

    @Test
    @DisplayName("toQualityMap rejects null/blank URLs")
    void toQualityMapRejectsBlanks() {
        assertThat(PlaywrightSniffDecoder.toQualityMap(null)).isEmpty();
        assertThat(PlaywrightSniffDecoder.toQualityMap(new InterceptedVideo(null, "video/mp4", 0)))
                .isEmpty();
        assertThat(PlaywrightSniffDecoder.toQualityMap(new InterceptedVideo("", "video/mp4", 0)))
                .isEmpty();
        assertThat(PlaywrightSniffDecoder.toQualityMap(new InterceptedVideo("   ", "video/mp4", 0)))
                .isEmpty();
    }
}
