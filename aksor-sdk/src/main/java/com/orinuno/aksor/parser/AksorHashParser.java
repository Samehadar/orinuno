package com.orinuno.aksor.parser;

import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extracts the 32-character hash that identifies a video on {@code player.aksor.tv}. Accepts the
 * canonical {@code https://player.aksor.tv/video/<hash>} embed URL plus a few alternate shapes
 * Aksor's iframe loader has shipped over time.
 */
public final class AksorHashParser {

    private static final Pattern HASH = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern IFRAME_PATH =
            Pattern.compile("(?i)player\\.aksor\\.tv/(?:video|embed)/([a-f0-9]{32})");

    private AksorHashParser() {}

    public static Optional<String> extract(@Nullable String iframeUrl) {
        if (iframeUrl == null || iframeUrl.isBlank()) {
            return Optional.empty();
        }
        var m = IFRAME_PATH.matcher(iframeUrl);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        // Last-resort: any 32-char hex run in the value.
        var bare = HASH.matcher(iframeUrl);
        return bare.find() ? Optional.of(bare.group()) : Optional.empty();
    }

    public static boolean looksLikeHash(@Nullable String value) {
        return value != null && HASH.matcher(value).matches();
    }
}
