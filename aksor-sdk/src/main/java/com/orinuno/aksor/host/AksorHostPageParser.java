package com.orinuno.aksor.host;

import com.orinuno.aksor.model.AksorAnime;
import java.net.URI;
import reactor.core.publisher.Mono;

/**
 * Per-host page parser contract. Implementations encapsulate everything host-specific: URL match
 * rules, anime-id extraction from page HTML, and the host's episodes API call. The Aksor-side
 * contract (player iframe hash → MPD URL) is host-independent and lives in the SDK core.
 *
 * <p>Reactive on purpose — host parsers usually need to make at least one HTTP call (e.g. {@code
 * /api/anime/{id}/videos} on yummyani.me) to enumerate episodes, so they cannot return a
 * synchronous answer the way Jsoup-only parsers do.
 */
public interface AksorHostPageParser {

    /** Stable identifier used in logs and metrics (e.g. {@code "yummyani"}). */
    String hostId();

    /** {@code true} if this parser handles the given page URL's host. */
    boolean supports(URI pageUrl);

    /**
     * Resolve a host page URL to an {@link AksorAnime} with episodes. Each episode must carry a
     * valid Aksor hash; episode {@code qualities} are filled in later by the pipeline.
     */
    Mono<AksorAnime> resolve(String pageUrl);
}
