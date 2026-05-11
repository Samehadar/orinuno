package com.orinuno.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.repository.ContentRepository;
import com.orinuno.repository.EpisodeVariantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end coverage for the ARCH-0017-FOLLOWUP-POSTER pipeline on the orinuno side. Boots the
 * full Spring stack (Liquibase + MyBatis + Jackson) against a real MySQL Testcontainers instance,
 * seeds {@link KodikContent} rows with crafted {@code material_data} / {@code screenshots} JSON,
 * and asserts the resulting {@code GET /api/v1/source-events/ready} payload exposes the right
 * {@code SourceContentInfo} poster URLs.
 *
 * <p>This test is intentionally <em>not</em> a unit test of {@link
 * com.orinuno.mapper.SourceEventMapper} — that exists in {@code SourceEventMapperTest}. Here we
 * lock the wire-format end-to-end so a regression in any of (a) MyBatis result mapping for {@code
 * material_data}, (b) {@code SourceEventMapper} parsing, (c) {@code SourceEventController} JSON
 * serialisation surfaces immediately.
 *
 * <p>Tagged {@code "e2e"} — excluded from default {@code mvn test}; run with {@code mvn test -Pe2e}
 * or {@code mvn test -Dgroups=e2e}.
 */
@Tag("e2e")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "orinuno.kodik.validate-on-startup=false",
            "orinuno.kodik.auto-discovery-enabled=false",
            "orinuno.kodik.bootstrap-from-env=false",
            "orinuno.kodik.token=poster-it-fake-token",
            "orinuno.playwright.enabled=false",
            "orinuno.security.api-key=",
            "orinuno.requests.worker-poll-ms=60000",
            "orinuno.cache.reference.enabled=false",
            "spring.liquibase.contexts=default"
        })
class SourceEventsPosterIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno")
                    .withUsername("orinuno")
                    .withPassword("orinuno")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired private WebTestClient webClient;
    @Autowired private ContentRepository contentRepository;
    @Autowired private EpisodeVariantRepository episodeVariantRepository;

    @Test
    @DisplayName(
            "happy-path: material_data → posterUrl/bigPosterUrl from poster_url_original;"
                    + " screenshots[] → screenshotUrls; trailerUrls absent (NON_EMPTY)")
    void posterUrlsAreSerialisedFromMaterialData() {
        KodikContent seed =
                KodikContent.builder()
                        .kodikId("poster-it-happy")
                        .type("movie")
                        .title("Poster IT Happy")
                        .titleOrig("Poster IT Happy Original")
                        .year(2026)
                        .kinopoiskId("kp-h-" + System.nanoTime())
                        .materialData(
                                "{"
                                        + "\"poster_url_original\":\"https://kp/big.jpg\","
                                        + "\"poster_url\":\"https://kp/small.jpg\","
                                        + "\"anime_poster_url\":\"https://kodik/anime.jpg\""
                                        + "}")
                        .screenshots("[\"https://kodik/s1.jpg\",\"https://kodik/s2.jpg\"]")
                        .build();
        contentRepository.insert(seed);
        episodeVariantRepository.insert(decodedVariant(seed.getId(), 600L));

        Map<String, Object> info = pickInfoForSeededContent(seed.getId());

        assertThat(info.get("posterUrl")).isEqualTo("https://kp/big.jpg");
        assertThat(info.get("bigPosterUrl")).isEqualTo("https://kp/big.jpg");
        assertThat(info.get("screenshotUrls"))
                .isEqualTo(List.of("https://kodik/s1.jpg", "https://kodik/s2.jpg"));
        assertThat(info)
                .as("trailerUrls must be absent — NON_EMPTY contract drops empty lists")
                .doesNotContainKey("trailerUrls");
    }

    @Test
    @DisplayName(
            "fallback chain: only anime_poster_url set → posterUrl = anime_poster_url,"
                    + " bigPosterUrl absent")
    void posterUrlFallsThroughKindSpecialisedFields() {
        KodikContent seed =
                KodikContent.builder()
                        .kodikId("poster-it-fallback")
                        .type("anime-serial")
                        .title("Poster IT Fallback")
                        .year(2026)
                        .kinopoiskId("kp-f-" + System.nanoTime())
                        .materialData("{\"anime_poster_url\":\"https://kodik/anime.jpg\"}")
                        .build();
        contentRepository.insert(seed);
        episodeVariantRepository.insert(decodedVariant(seed.getId(), 601L));

        Map<String, Object> info = pickInfoForSeededContent(seed.getId());

        assertThat(info.get("posterUrl")).isEqualTo("https://kodik/anime.jpg");
        assertThat(info)
                .as("bigPosterUrl must be absent when poster_url_original is missing")
                .doesNotContainKey("bigPosterUrl");
        assertThat(info)
                .as("screenshotUrls must be absent when screenshots column is null")
                .doesNotContainKey("screenshotUrls");
    }

    @Test
    @DisplayName(
            "robustness: valid material_data without any poster keys → 200 OK, posters absent,"
                    + " chrome (titleRu / year / externalIds) still present. MySQL stores"
                    + " material_data as a JSON column so genuinely corrupt JSON cannot be"
                    + " inserted at all — the defensive parse path in SourceEventMapper.parse*"
                    + " is exercised here against a syntactically valid blob that simply lacks"
                    + " every poster_url / *_poster_url field, which is the realistic regression"
                    + " surface (Kodik occasionally returns material_data with only ratings /"
                    + " genres for partially-indexed titles).")
    void posterFieldsAbsentWhenMaterialDataLacksPosterKeys() {
        KodikContent seed =
                KodikContent.builder()
                        .kodikId("poster-it-no-posters")
                        .type("movie")
                        .title("Poster IT No Posters")
                        .year(2026)
                        .kinopoiskId("kp-b-" + System.nanoTime())
                        .imdbId("tt9999999")
                        .materialData(
                                "{\"description\":\"only chrome present\","
                                        + "\"genres\":[\"Drama\"]}")
                        .build();
        contentRepository.insert(seed);
        episodeVariantRepository.insert(decodedVariant(seed.getId(), 602L));

        Map<String, Object> info = pickInfoForSeededContent(seed.getId());

        assertThat(info).doesNotContainKey("posterUrl");
        assertThat(info).doesNotContainKey("bigPosterUrl");
        assertThat(info).doesNotContainKey("screenshotUrls");
        assertThat(info.get("titleRu")).isEqualTo("Poster IT No Posters");
        assertThat(info.get("year")).isEqualTo(2026);
        @SuppressWarnings("unchecked")
        Map<String, Object> external = (Map<String, Object>) info.get("externalIds");
        assertThat(external).isNotNull();
        assertThat(external.get("imdbId")).isEqualTo("tt9999999");
    }

    private Map<String, Object> pickInfoForSeededContent(Long contentId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events =
                webClient
                        .get()
                        .uri("/api/v1/source-events/ready?limit=200")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(List.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(events)
                .as("source-events/ready must return at least the seeded content")
                .isNotNull()
                .isNotEmpty();
        Map<String, Object> match =
                events.stream()
                        .filter(
                                e ->
                                        contentId
                                                .toString()
                                                .equals(
                                                        ((Map<String, Object>) e.get("identifier"))
                                                                .get("sourceId")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "seeded content id="
                                                        + contentId
                                                        + " not present in"
                                                        + " /api/v1/source-events/ready response"));
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) match.get("info");
        assertThat(info)
                .as("expected info object on the SourceCatalogEvent for content id=%d", contentId)
                .isNotNull();
        return info;
    }

    private static KodikEpisodeVariant decodedVariant(Long contentId, Long variantSeed) {
        return KodikEpisodeVariant.builder()
                .contentId(contentId)
                .seasonNumber(1)
                .episodeNumber(1)
                .translationId(Math.toIntExact(variantSeed))
                .translationTitle("Russian dub")
                .translationType("voice")
                .quality("720p")
                .kodikLink("//kodik.example/poster-it-" + variantSeed)
                .mp4Link("https://cdn.example.com/poster-it-" + variantSeed + ".mp4")
                .mp4LinkDecodedAt(LocalDateTime.now())
                .build();
    }
}
