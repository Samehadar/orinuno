package com.orinuno.client.http;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the User-Agent strings orinuno sends to Kodik (iframe HTML, player JS,
 * decoder POST, CDN HLS segment fetch, Playwright stealth context, token auto-discovery scrape).
 *
 * <p>Why centralise? Before UA-1 the same UA literal lived in five files:
 *
 * <ul>
 *   <li>{@code KodikVideoDecoderService.randomUserAgent()} — pool of 5 strings used per request.
 *   <li>{@code WebClientConfiguration.kodikCdnWebClient()} — single CHROME_UA on the CDN client.
 *   <li>{@code KodikTokenAutoDiscovery.discoverLegacyToken()} — single hard-coded Chrome string.
 *   <li>{@code PlaywrightVideoFetcher.newStealthContext()} — single hard-coded Chrome string.
 *   <li>{@code PlaywrightVideoFetcher} HLS segment download — abbreviated string.
 * </ul>
 *
 * That made it impossible to bump the Chrome major version in one place, and risky to introduce a
 * Firefox tier without auditing every site. This provider:
 *
 * <ul>
 *   <li>Owns the canonical pool of UAs (modern Chrome + Firefox on Win / macOS / Linux).
 *   <li>Exposes {@link #randomDesktop()} for per-request rotation (decoder, segment fetch).
 *   <li>Exposes {@link #stableDesktop()} for callers that need a single, sticky UA per session
 *       (Playwright context, CDN WebClient bean, calendar fetcher's "honest" UA is a separate
 *       contract — see {@link #orinunoBot(String)}).
 * </ul>
 *
 * <p>The pool is package-private and final at compile time. To rotate the pool, edit this file and
 * bump the version comment — that's the single audit point.
 *
 * <p>NB: the KodikCalendarHttpClient continues to send {@code orinuno/1.0 ...} as its UA because
 * the public calendar endpoint is operated by Kodik for honest consumers and an "honest bot UA" is
 * the right contract there. We expose {@link #orinunoBot(String)} for that case so the literal is
 * still owned by this class.
 */
@Component
public class RotatingUserAgentProvider {

    /**
     * Canonical desktop pool — modern Chrome / Firefox on Win 10, macOS Big Sur / Sequoia, Linux.
     * Refreshed 2026-05-02 as part of UA-1.
     */
    static final List<String> DESKTOP_POOL =
            List.of(
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like"
                            + " Gecko) Chrome/135.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML,"
                            + " like Gecko) Chrome/135.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                            + " Chrome/135.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101"
                            + " Firefox/128.0",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:128.0) Gecko/20100101"
                            + " Firefox/128.0");

    /** Stable per-process pick. Used as the sticky UA for callers that need session affinity. */
    private final String stableDesktop;

    public RotatingUserAgentProvider() {
        this.stableDesktop =
                DESKTOP_POOL.get(ThreadLocalRandom.current().nextInt(DESKTOP_POOL.size()));
    }

    /** A randomly-rotated UA from the desktop pool; suitable for per-request rotation. */
    public String randomDesktop() {
        return DESKTOP_POOL.get(ThreadLocalRandom.current().nextInt(DESKTOP_POOL.size()));
    }

    /**
     * The same UA for every call within the lifetime of this provider instance — picked at random
     * from {@link #DESKTOP_POOL} on construction. Use this for callers that need session affinity
     * (e.g. Playwright contexts, CDN WebClient beans).
     */
    public String stableDesktop() {
        return stableDesktop;
    }

    /**
     * Honest "I am orinuno" UA suitable for endpoints operated by Kodik for legitimate consumers
     * (e.g. the public calendar dump). The {@code suffix} typically describes the workload, e.g.
     * {@code "calendar-watcher"}.
     */
    public static String orinunoBot(String suffix) {
        return "orinuno/1.0 (+https://github.com/orinuno) " + Objects.requireNonNull(suffix);
    }
}
