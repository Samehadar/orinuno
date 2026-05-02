package com.orinuno.service.decoder;

import com.orinuno.service.PlaywrightVideoFetcher;
import com.orinuno.service.PlaywrightVideoFetcher.InterceptedVideo;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * DECODE-8 — Playwright-backed network-sniff fallback decoder.
 *
 * <p>When the regex/JS-based {@link com.orinuno.service.KodikVideoDecoderService} fails (Kodik
 * shipped a fresh player JS shape that breaks the regex; rare but recurring), we open the Kodik
 * player in a headless Chromium tab via {@link PlaywrightVideoFetcher} and capture the CDN URL from
 * the network. The capture is single-quality — the browser fetches whatever quality Kodik decides
 * to serve it (usually the master HLS playlist), so we tag the result as quality {@code "auto"} so
 * the {@code pickBestQualityEntry} picker drops it (it filters for numeric keys) and we persist the
 * URL via the orchestrator's "first-non-empty-bucket" path instead.
 *
 * <p>{@link PlaywrightVideoFetcher} may be disabled in configuration; we accept it as an {@link
 * ObjectProvider} so this bean is constructible in unit tests + degrades to an unavailable result
 * when Playwright isn't around.
 */
@Slf4j
@Component
public class PlaywrightSniffDecoder {

    /**
     * Quality bucket key used for sniff results. Intentionally NOT numeric — see {@link
     * com.orinuno.service.ParserService#pickBestQualityEntry} which drops non-numeric keys. The
     * orchestrator's persistence layer treats this key specially.
     */
    static final String SNIFF_QUALITY_KEY = "auto";

    private final ObjectProvider<PlaywrightVideoFetcher> fetcherProvider;

    public PlaywrightSniffDecoder(ObjectProvider<PlaywrightVideoFetcher> fetcherProvider) {
        this.fetcherProvider = fetcherProvider;
    }

    /** {@code true} if the underlying Playwright browser was successfully initialised. */
    public boolean isAvailable() {
        PlaywrightVideoFetcher fetcher = fetcher();
        return fetcher != null && fetcher.isAvailable();
    }

    /**
     * Sniff the CDN video URL for the given Kodik player link. Returns an empty map when:
     *
     * <ul>
     *   <li>Playwright is disabled or failed to initialise
     *   <li>The headless browser timed out before any candidate CDN URL appeared
     *   <li>The interception failed for any other reason
     * </ul>
     *
     * <p>Errors are logged but never propagated as a Mono error — DECODE-8 is a fallback path and a
     * failure here means "fall back further" (which usually means "give up and try later").
     */
    public Mono<Map<String, String>> sniff(String kodikLink) {
        PlaywrightVideoFetcher fetcher = fetcher();
        if (fetcher == null || !fetcher.isAvailable()) {
            log.debug("DECODE-8: Playwright unavailable, sniff fallback disabled");
            return Mono.just(Map.of());
        }
        return fetcher.interceptVideoUrl(kodikLink)
                .map(PlaywrightSniffDecoder::toQualityMap)
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "DECODE-8: sniff failed for {}: {} ({})",
                                    kodikLink,
                                    ex.getClass().getSimpleName(),
                                    ex.getMessage());
                            return Mono.just(Map.of());
                        });
    }

    private PlaywrightVideoFetcher fetcher() {
        return fetcherProvider == null ? null : fetcherProvider.getIfAvailable();
    }

    static Map<String, String> toQualityMap(InterceptedVideo intercepted) {
        if (intercepted == null || intercepted.url() == null || intercepted.url().isBlank()) {
            return Map.of();
        }
        return Map.of(SNIFF_QUALITY_KEY, intercepted.url());
    }
}
