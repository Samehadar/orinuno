package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kodik.client.KodikApiClient;
import com.kodik.client.dto.KodikSearchRequest;
import com.kodik.client.dto.KodikSearchResponse;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.mapper.EntityFactory;
import com.orinuno.mapper.SourceEventMapper;
import com.orinuno.model.KodikContent;
import java.time.Clock;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drift-detection probe for the ARCH-0017-FOLLOWUP-POSTER pipeline. Hits the real Kodik {@code
 * /search} endpoint with a known shikimoriId, mirrors the response into a {@link KodikContent} via
 * the production {@link EntityFactory}, then runs the production {@link SourceEventMapper} and
 * asserts that the resulting {@link SourceContentInfo} carries a non-blank {@code posterUrl}.
 *
 * <p>This test exists for one narrow purpose: if Kodik renames or removes {@code
 * material_data.poster_url_original} (or its kind-specialised fallbacks {@code poster_url} / {@code
 * anime_poster_url} / {@code drama_poster_url}), {@link SourceEventMapper} silently emits {@code
 * SourceContentInfo} without a poster URL, the kodik-parser download path becomes a no-op, and
 * posters disappear from kin's MinIO bucket. Default {@code mvn test} runs (without {@code
 * KODIK_TOKEN}) cannot detect this. A nightly run with the token gets a hard failure here as soon
 * as the upstream shape drifts.
 *
 * <p>Skipped automatically when {@code KODIK_TOKEN} is unset. Tagged {@code "live"} so default
 * surefire runs (which exclude {@code e2e} / {@code live}) skip it as well, but the {@code e2e}
 * profile's {@code groups=e2e} excludes it too — run explicitly with {@code mvn -pl orinuno-app
 * test -Dgroups=live -Dtest=KodikPosterShapeLiveIT}.
 */
@Tag("live")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "KODIK_TOKEN", matches = ".+")
class KodikPosterShapeLiveIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("orinuno_live")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("orinuno.kodik.token", () -> System.getenv("KODIK_TOKEN"));
        registry.add("orinuno.kodik.request-delay-ms", () -> "200");
        registry.add(
                "orinuno.kodik.token-file",
                () -> {
                    try {
                        java.nio.file.Path dir =
                                java.nio.file.Files.createTempDirectory("kodik-tokens-poster-");
                        return dir.resolve("kodik_tokens.json").toAbsolutePath().toString();
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        registry.add("orinuno.kodik.validate-on-startup", () -> "false");
        registry.add("orinuno.kodik.auto-discovery-enabled", () -> "false");
        registry.add("orinuno.playwright.enabled", () -> "false");
    }

    @Autowired private KodikApiClient kodikApiClient;

    @Test
    @DisplayName(
            "Kodik /search response for shikimoriId=20 (Naruto) must still expose a poster URL"
                    + " field mappable to SourceContentInfo.posterUrl (drift-detection probe)")
    void kodikStillExposesPosterUrlInMaterialData() {
        KodikSearchRequest request =
                KodikSearchRequest.builder()
                        .shikimoriId("20")
                        .withMaterialData(true)
                        .limit(5)
                        .build();

        KodikSearchResponse response = kodikApiClient.search(request).block();

        assertThat(response)
                .as("Kodik /search must return a structured response when KODIK_TOKEN is valid")
                .isNotNull();
        assertThat(response.getResults())
                .as(
                        "Kodik /search for shikimoriId=20 (Naruto) used to return at least one"
                                + " result; a zero-result response signals the canary shikimoriId"
                                + " itself was retired upstream — pick a different stable id"
                                + " rather than loosening this assertion")
                .isNotNull()
                .isNotEmpty();

        KodikSearchResponse.Result first = response.getResults().get(0);
        KodikContent content = EntityFactory.createContent(first);
        SourceCatalogEvent event =
                SourceEventMapper.toEvent(content, Collections.emptyList(), Clock.systemUTC());

        SourceContentInfo info = infoOf(event);

        assertThat(info.posterUrl())
                .as(
                        "ARCH-0017-FOLLOWUP-POSTER drift probe: Kodik no longer exposes"
                                + " poster_url_original / poster_url / anime_poster_url /"
                                + " drama_poster_url in material_data — the kodik-parser MinIO"
                                + " upload path will silently become a no-op")
                .isNotNull()
                .startsWith("https://");

        if (info.bigPosterUrl() != null) {
            assertThat(info.bigPosterUrl())
                    .as(
                            "if Kodik exposes poster_url_original it must be an absolute https"
                                    + " URL")
                    .startsWith("https://");
        }
    }

    private static SourceContentInfo infoOf(SourceCatalogEvent event) {
        if (event instanceof SourceCatalogEvent.MovieDiscovered movie) {
            return movie.info();
        }
        if (event instanceof SourceCatalogEvent.SeriesDiscovered series) {
            return series.info();
        }
        if (event instanceof SourceCatalogEvent.TitleObserved observed) {
            return observed.info();
        }
        throw new AssertionError(
                "expected MovieDiscovered/SeriesDiscovered/TitleObserved, got "
                        + event.getClass().getSimpleName());
    }
}
