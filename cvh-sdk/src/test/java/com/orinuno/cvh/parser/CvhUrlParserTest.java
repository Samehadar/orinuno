package com.orinuno.cvh.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CvhUrlParserTest {

    @Test
    void parsesExpiresFromSignedHlsUrl() {
        String url =
                "https://ok6-1.vkuser.net/video.m3u8?cmd=videoPlayerCdn"
                        + "&expires=1810000000000&sig=abc&srcIp=1.2.3.4";
        assertThat(CvhUrlParser.parseExpiresFromUrl(url))
                .contains(Instant.ofEpochMilli(1810000000000L));
    }

    @Test
    void absentExpiresReturnsEmpty() {
        assertThat(CvhUrlParser.parseExpiresFromUrl("https://x.test/?sig=abc")).isEmpty();
        assertThat(CvhUrlParser.parseExpiresFromUrl(null)).isEmpty();
        assertThat(CvhUrlParser.parseExpiresFromUrl("")).isEmpty();
        assertThat(CvhUrlParser.parseExpiresFromUrl("not a url")).isEmpty();
    }

    @Test
    void malformedExpiresReturnsEmpty() {
        assertThat(CvhUrlParser.parseExpiresFromUrl("https://x.test/?expires=notnum")).isEmpty();
        assertThat(CvhUrlParser.parseExpiresFromUrl("https://x.test/?expires=")).isEmpty();
    }

    @Test
    void extractsSlug() {
        assertThat(CvhUrlParser.extractSlug("https://jut-su.works/all-you-need-is-kill"))
                .isEqualTo("all-you-need-is-kill");
        assertThat(CvhUrlParser.extractSlug("https://jut-su.works/all-you-need-is-kill/"))
                .isEqualTo("all-you-need-is-kill");
        assertThat(CvhUrlParser.extractSlug("https://jut-su.works/all-you-need-is-kill?utm=1"))
                .isEqualTo("all-you-need-is-kill");
        assertThat(CvhUrlParser.extractSlug("")).isEmpty();
    }
}
