package com.orinuno.cvh.host.jutsu;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.host.CvhHostRegistry;
import com.orinuno.cvh.model.AnimeContent;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Live HTML-parser tests against jut-su.works. Skipped unless {@code CVH_LIVE_TESTS=1}. Pure
 * page-parse path — no CVH plapi calls. Verifies the Jsoup selectors still match the live DOM on a
 * portfolio of real pages (films, serials, ongoing).
 */
@EnabledIfEnvironmentVariable(named = "CVH_LIVE_TESTS", matches = "1|true|TRUE|yes")
class JutsuCvhHostLiveTest {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/147.0.0.0 Safari/537.36";
    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(20);

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env " + key + " is required for live tests");
        }
        return v;
    }

    @Test
    void supportsRealJutsuUrl() {
        String url = requiredEnv("CVH_LIVE_TEST_URL");
        assertThat(new JutsuCvhHost("mali").supports(URI.create(url))).isTrue();
    }

    @Test
    void hostRegistryResolvesJutsuLive() {
        String url = requiredEnv("CVH_LIVE_TEST_URL");
        CvhHostRegistry registry = new CvhHostRegistry(List.of(new JutsuCvhHost("mali")));
        assertThat(registry.resolve(url)).isPresent();
        assertThat(registry.resolve("https://other.test/x")).isEmpty();
    }

    @Test
    void parsesLiveJutsuPage() {
        String url = requiredEnv("CVH_LIVE_TEST_URL");
        AnimeContent c = new JutsuCvhHost("mali").parse(fetchHtml(url), url);
        assertCvhEmbeddingPage(c);
    }

    /**
     * Common-shape metadata extraction must work on every jut-su page regardless of which player is
     * embedded. The four URLs below mix CVH-embedding ({@code all-you-need-is-kill}) and Kodik-only
     * legacy pages to lock in selector stability across both shapes.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "https://jut-su.works/all-you-need-is-kill",
                "https://jut-su.works/shoujo-tachi-wa-kouya-wo-mezasu",
                "https://jut-su.works/konjiki-no-gash-bell",
                "https://jut-su.works/mahou-no-princess-minky-momo-yume-wo-dakishimete",
            })
    void commonMetadataSelectorsStableAcrossPagePortfolio(String url) {
        AnimeContent c = new JutsuCvhHost("mali").parse(fetchHtml(url), url);
        assertThat(c.title()).as("title").isNotBlank();
        assertThat(c.posterUrl()).as("poster URL").startsWith("http");
        assertThat(c.slug()).as("slug").isNotBlank();
    }

    private static void assertCvhEmbeddingPage(AnimeContent c) {
        assertThat(c.title()).as("page title").isNotBlank();
        assertThat(c.posterUrl()).as("poster URL").startsWith("http");
        assertThat(c.cvhTitleId())
                .as("data-title-id from <video-player>")
                .isNotBlank()
                .matches("\\d+");
        assertThat(c.cvhPublisherId()).isNotBlank().matches("\\d+");
        assertThat(c.cvhAggregator()).isNotBlank();
    }

    private static String fetchHtml(String url) {
        return WebClient.builder()
                .defaultHeader("User-Agent", DESKTOP_UA)
                .defaultHeader("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .defaultHeader(
                        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .switchIfEmpty(Mono.error(new IllegalStateException("Empty response from " + url)))
                .block(BLOCKING_TIMEOUT);
    }
}
