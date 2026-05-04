package com.orinuno.jutsu.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class JutsuHtmlCharsetTest {

    @Test
    void resolveHonoursContentTypeCharset() {
        MediaType ct = MediaType.parseMediaType("text/html; charset=UTF-8");

        assertThat(JutsuHtmlCharset.resolve(ct)).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void resolveFallsBackToWindows1251WhenContentTypeIsNull() {
        assertThat(JutsuHtmlCharset.resolve(null)).isEqualTo(Charset.forName("windows-1251"));
    }

    @Test
    void resolveFallsBackToWindows1251WhenContentTypeHasNoCharset() {
        MediaType ct = MediaType.parseMediaType("text/html");

        assertThat(JutsuHtmlCharset.resolve(ct)).isEqualTo(Charset.forName("windows-1251"));
    }

    @Test
    void decodeWindows1251Cyrillic() {
        byte[] bytes = "Атака титанов".getBytes(Charset.forName("windows-1251"));

        String decoded = JutsuHtmlCharset.decode(bytes, null);

        assertThat(decoded).isEqualTo("Атака титанов");
    }

    @Test
    void decodeUtf8WhenContentTypeAdvertisesIt() {
        byte[] bytes = "Божественный сад".getBytes(StandardCharsets.UTF_8);
        MediaType ct = MediaType.parseMediaType("text/html; charset=UTF-8");

        String decoded = JutsuHtmlCharset.decode(bytes, ct);

        assertThat(decoded).isEqualTo("Божественный сад");
    }

    @Test
    void decodeNullBytesReturnsEmptyString() {
        assertThat(JutsuHtmlCharset.decode(null, null)).isEmpty();
    }

    @Test
    void decodeEmptyBytesReturnsEmptyString() {
        assertThat(JutsuHtmlCharset.decode(new byte[0], null)).isEmpty();
    }

    @Test
    void defaultIsWindows1251OnAllSupportedJvms() {
        // Sanity: the constant is stable so callers can compare against it without recomputing.
        assertThat(JutsuHtmlCharset.DEFAULT).isEqualTo(Charset.forName("windows-1251"));
    }
}
