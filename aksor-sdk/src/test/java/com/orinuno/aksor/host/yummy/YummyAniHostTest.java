package com.orinuno.aksor.host.yummy;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.aksor.model.AksorAnime;
import com.orinuno.aksor.model.AksorEpisode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class YummyAniHostTest {

    private static String fixture(String name) {
        try {
            return new String(
                    Objects.requireNonNull(
                                    YummyAniHostTest.class.getResource("/hosts/yummy/" + name))
                            .openStream()
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("fixture " + name, ex);
        }
    }

    @Test
    void supportsYummyHosts() {
        YummyAniHost host =
                new YummyAniHost(
                        com.orinuno.aksor.AksorConfig.builder().build(),
                        org.springframework.web.reactive.function.client.WebClient.builder());
        assertThat(host.supports(URI.create("https://yummyani.me/catalog/x"))).isTrue();
        assertThat(host.supports(URI.create("https://old.yummyani.me/catalog/x"))).isTrue();
        assertThat(host.supports(URI.create("https://other.test/x"))).isFalse();
        assertThat(host.supports(null)).isFalse();
    }

    @Test
    void parsePageExtractsAnimeIdTitleAndPoster() {
        YummyAniHost.PageMeta meta =
                YummyAniHost.parsePage(
                        fixture("page.html"),
                        "https://old.yummyani.me/catalog/item/monolog-farmatsevta");
        assertThat(meta.animeId()).isEqualTo("10531");
        assertThat(meta.title()).isEqualTo("Монолог фармацевта");
        assertThat(meta.posterUrl()).startsWith("https://static.yani.tv");
        assertThat(meta.slug()).isEqualTo("monolog-farmatsevta");
    }

    @Test
    void buildAnimeKeepsOnlyAksorEpisodes() {
        YummyAniHost.PageMeta meta =
                new YummyAniHost.PageMeta(
                        "10531",
                        "monolog-farmatsevta",
                        "Монолог фармацевта",
                        "https://static.yani.tv/p.jpg");
        AksorAnime anime =
                YummyAniHost.buildAnime(
                        meta,
                        "https://old.yummyani.me/catalog/item/monolog-farmatsevta",
                        fixture("videos.json"));
        assertThat(anime.episodes()).hasSize(2);
        AksorEpisode first = anime.episodes().get(0);
        assertThat(first.hash()).isEqualTo("248a4ad8181c6e5741371525d70e446b");
        assertThat(first.number()).isEqualTo("1");
        assertThat(first.dubbing()).contains("AniLibria");
        assertThat(first.ending()).isNotNull();
        assertThat(first.ending().timeSec()).isEqualTo(1310);
        AksorEpisode second = anime.episodes().get(1);
        assertThat(second.opening()).isNotNull();
        assertThat(second.opening().lengthSec()).isEqualTo(89);
    }
}
