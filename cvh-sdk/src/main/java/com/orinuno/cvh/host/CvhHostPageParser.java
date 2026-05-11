package com.orinuno.cvh.host;

import com.orinuno.cvh.model.AnimeContent;
import java.net.URI;

/**
 * Per-host page parser contract. Implementations encapsulate everything host-specific: URL match
 * rules and Jsoup selectors for title metadata. The CVH-side contract ({@code <video-player>}
 * attributes, plapi shape, signed CDN URLs) is host-independent and lives in the SDK core.
 *
 * <p>Add a new host site by implementing this interface in any package and registering the instance
 * through {@link com.orinuno.cvh.CvhClient.Builder#registerHost}. Zero changes to SDK core are
 * required.
 */
public interface CvhHostPageParser {

    /**
     * Stable identifier used in logs and metrics (e.g. {@code "jutsu"}). Lowercase, no slashes,
     * stable across versions.
     */
    String hostId();

    /**
     * @return {@code true} if this parser handles pages on the given URL's host.
     */
    boolean supports(URI pageUrl);

    /** Parse the host page HTML into structured metadata. Must not throw on missing selectors. */
    AnimeContent parse(String html, String pageUrl);
}
