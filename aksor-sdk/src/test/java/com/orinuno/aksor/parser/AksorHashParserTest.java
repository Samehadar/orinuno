package com.orinuno.aksor.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AksorHashParserTest {

    @Test
    void extractsFromVideoPath() {
        assertThat(
                        AksorHashParser.extract(
                                "https://player.aksor.tv/video/248a4ad8181c6e5741371525d70e446b"))
                .contains("248a4ad8181c6e5741371525d70e446b");
    }

    @Test
    void extractsFromEmbedPath() {
        assertThat(
                        AksorHashParser.extract(
                                "https://player.aksor.tv/embed/715a11475e78f4833dcd38e426ca007b?x=1"))
                .contains("715a11475e78f4833dcd38e426ca007b");
    }

    @Test
    void fallsBackToBareHashInString() {
        assertThat(AksorHashParser.extract("hash=248a4ad8181c6e5741371525d70e446b&other=1"))
                .contains("248a4ad8181c6e5741371525d70e446b");
    }

    @Test
    void rejectsNonHash() {
        assertThat(AksorHashParser.extract("https://example.com/foo")).isEmpty();
        assertThat(AksorHashParser.extract("")).isEmpty();
        assertThat(AksorHashParser.extract(null)).isEmpty();
    }

    @Test
    void looksLikeHashRecognisesValid() {
        assertThat(AksorHashParser.looksLikeHash("248a4ad8181c6e5741371525d70e446b")).isTrue();
        assertThat(AksorHashParser.looksLikeHash("UPPERCASE000000000000000000000000")).isFalse();
        assertThat(AksorHashParser.looksLikeHash("tooshort")).isFalse();
    }
}
