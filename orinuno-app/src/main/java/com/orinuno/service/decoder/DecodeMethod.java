package com.orinuno.service.decoder;

/**
 * DECODE-8 — discriminator stored in {@code kodik_episode_variant.decode_method}. Stored as a
 * string so unknown methods from a newer orinuno can be read by older code (and vice versa) with no
 * migration drama.
 */
public enum DecodeMethod {
    /** Regex/JS path via {@link com.orinuno.service.KodikVideoDecoderService}. */
    REGEX,
    /**
     * Playwright network-sniff fallback via {@link
     * com.orinuno.service.PlaywrightVideoFetcher#interceptVideoUrl(String)}.
     */
    SNIFF
}
