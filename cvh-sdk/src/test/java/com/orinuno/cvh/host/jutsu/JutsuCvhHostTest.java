package com.orinuno.cvh.host.jutsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.cvh.model.AnimeContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class JutsuCvhHostTest {

    private final JutsuCvhHost host = new JutsuCvhHost("mali");

    private static String fixture(String name) {
        try {
            var url = JutsuCvhHostTest.class.getResource("/hosts/jutsu/" + name);
            return new String(
                    Objects.requireNonNull(url, name).openStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load fixture " + name, ex);
        }
    }

    @Test
    void hostIdAndSupports() {
        assertThat(host.hostId()).isEqualTo("jutsu");
        assertThat(host.supports(URI.create("https://jut-su.works/foo"))).isTrue();
        assertThat(host.supports(URI.create("https://www.jut-su.works/foo"))).isTrue();
        assertThat(host.supports(URI.create("https://JUT-SU.WORKS/foo"))).isTrue();
        assertThat(host.supports(URI.create("https://example.com/foo"))).isFalse();
        assertThat(host.supports(null)).isFalse();
    }

    @Test
    void constructorRequiresAggregator() {
        assertThatThrownBy(() -> new JutsuCvhHost("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JutsuCvhHost(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesFullCvhPage() {
        AnimeContent c =
                host.parse(
                        fixture("title-page-cvh.html"),
                        "https://jut-su.works/all-you-need-is-kill");
        assertThat(c.slug()).isEqualTo("all-you-need-is-kill");
        assertThat(c.title()).isEqualTo("Всё что тебе нужно — убивать");
        assertThat(c.titleOriginal()).isEqualTo("All You Need Is Kill");
        assertThat(c.genres()).containsExactly("Экшен", "Фантастика");
        assertThat(c.releaseDate()).isEqualTo("9 января 2026");
        assertThat(c.country()).isEqualTo("Япония");
        assertThat(c.description()).contains("Первый абзац").contains("Второй абзац");
        assertThat(c.posterUrl()).contains("/uploads/posters/61192.jpg");
        assertThat(c.cvhTitleId()).isEqualTo("61192");
        assertThat(c.cvhPublisherId()).isEqualTo("910");
        assertThat(c.cvhAggregator()).isEqualTo("mali");
        assertThat(c.cvhPriorityVoice()).isEqualTo("AniStar");
        assertThat(c.kodikIframeSrc()).isEqualTo("//kodikplayer.com/serial/12345/abc/720p");
        assertThat(c.rating()).isNotNull();
        assertThat(c.rating().value()).isEqualTo("8.4");
        assertThat(c.rating().count()).isEqualTo("1234");
        assertThat(c.rating().contentType()).isEqualTo("Movie");
        assertThat(c.rating().datePublished()).isEqualTo("2026-01-09");
    }

    @Test
    void pageWithoutCvhYieldsNullTitleId() {
        AnimeContent c = host.parse(fixture("title-page-no-cvh.html"), "https://jut-su.works/old");
        assertThat(c.cvhTitleId()).isNull();
        assertThat(c.cvhPublisherId()).isNull();
        assertThat(c.title()).isEqualTo("Старое аниме");
        assertThat(c.kodikIframeSrc()).isEqualTo("//kodikplayer.com/serial/0/y/720p");
    }

    @Test
    void aggregatorFallsBackToDefault() {
        String html =
                "<html><body><h1>X</h1><video-player data-title-id=\"1\""
                        + " data-publisher-id=\"2\"></video-player></body></html>";
        AnimeContent c = host.parse(html, "https://jut-su.works/x");
        assertThat(c.cvhAggregator()).isEqualTo("mali");
    }

    @Test
    void titleSuffixStripping() {
        String html = "<html><body><h1>Foo онлайн</h1></body></html>";
        assertThat(host.parse(html, "https://jut-su.works/foo").title()).isEqualTo("Foo");

        String html2 = "<html><body><h1>Bar смотреть онлайн</h1></body></html>";
        assertThat(host.parse(html2, "https://jut-su.works/bar").title()).isEqualTo("Bar");
    }
}
