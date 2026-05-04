package com.orinuno.jutsu.parser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/**
 * Shared charset resolution for jut.su HTML responses.
 *
 * <p>jut.su's PHP layer responds with {@code Content-Type: text/html; charset=windows-1251} on
 * almost every endpoint we care about, but we cannot rely on the header alone — Cloudflare
 * challenge pages, JSON-shaped error blobs, and certain static fallbacks omit the charset and
 * default to ASCII-compatible UTF-8. The catalog/info/notice parsers all need cyrillic-correct
 * String decoding (titles like "Атака титанов" or "Божественный сад у поместья Кусуноки" must
 * round-trip), so spelling the resolution out in one place keeps the SDK consistent.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>Content-Type charset, when present and parseable by the JVM.
 *   <li>{@code windows-1251} — jut.su's documented default.
 *   <li>{@code UTF-8} — last-resort fallback for environments where {@code windows-1251} is
 *       unavailable (truly exotic JVMs).
 * </ol>
 *
 * <p>The episode-page decoder ({@link com.orinuno.jutsu.decoder.JutsuDecoder}) and every new
 * jsoup-driven parser delegates to this helper so a single fix here propagates to all surfaces.
 */
public final class JutsuHtmlCharset {

    /** jut.su's documented default body encoding. */
    public static final Charset DEFAULT = forNameOrUtf8("windows-1251");

    private JutsuHtmlCharset() {}

    /**
     * Pick the charset to decode a jut.su response body with.
     *
     * @param contentType the parsed {@code Content-Type} header from the response, may be null
     * @return a non-null Charset (never throws — falls back to UTF-8 if the JVM lacks windows-1251)
     */
    public static Charset resolve(MediaType contentType) {
        if (contentType != null && contentType.getCharset() != null) {
            return contentType.getCharset();
        }
        return DEFAULT;
    }

    /**
     * Decode bytes to a String using {@link #resolve(MediaType)}. Returns the empty string when the
     * input is null or empty so parsers don't have to null-check before applying selectors.
     */
    public static String decode(byte[] bytes, MediaType contentType) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes, resolve(contentType));
    }

    private static Charset forNameOrUtf8(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception fallback) {
            return StandardCharsets.UTF_8;
        }
    }
}
