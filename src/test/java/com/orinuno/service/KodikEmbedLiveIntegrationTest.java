package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.client.embed.KodikIdType;
import com.orinuno.model.dto.EmbedLinkDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end live test for {@code GET /api/v1/embed/{idType}/{id}}. Hits the real Kodik {@code
 * /get-player} endpoint via {@link KodikEmbedService}, so a working {@code KODIK_TOKEN} is
 * mandatory — {@link EnabledIfEnvironmentVariable} silently skips the class otherwise.
 *
 * <p>Tagged {@code e2e} so the default {@code mvn test} skips it; opt in via {@code mvn test
 * -Pe2e}. Aligned with {@link KodikLiveIntegrationTest} on container setup, throttling, and token
 * registry isolation so both can run side-by-side without polluting each other.
 *
 * <p>Test data picks famously stable ids:
 *
 * <ul>
 *   <li>Shikimori {@code 20} — Naruto (TV serial), guaranteed serial result.
 *   <li>Kinopoisk {@code 326} — Shawshank Redemption (movie), guaranteed video result.
 *   <li>Imdb {@code 0903747} — Breaking Bad, also exercises the {@code tt}-prefix normalisation.
 * </ul>
 */
@Tag("e2e")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "60s")
@EnabledIfEnvironmentVariable(named = "KODIK_TOKEN", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KodikEmbedLiveIntegrationTest {

    @Container
    static MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("orinuno_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("orinuno.kodik.token", () -> System.getenv("KODIK_TOKEN"));
        registry.add("orinuno.kodik.request-delay-ms", () -> "200");
        registry.add(
                "orinuno.kodik.token-file",
                () -> {
                    try {
                        java.nio.file.Path dir =
                                java.nio.file.Files.createTempDirectory("kodik-tokens-embed-it-");
                        return dir.resolve("kodik_tokens.json").toAbsolutePath().toString();
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        registry.add("orinuno.kodik.validate-on-startup", () -> "false");
        registry.add("orinuno.kodik.auto-discovery-enabled", () -> "false");
    }

    @Autowired private WebTestClient webTestClient;

    @Test
    @Order(1)
    @DisplayName("Shikimori id resolves to a kodikplayer.com serial embed link")
    void shikimoriResolvesSerial() {
        webTestClient
                .get()
                .uri("/api/v1/embed/shikimori/20")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(EmbedLinkDto.class)
                .value(
                        dto -> {
                            assertThat(dto.idType()).isEqualTo(KodikIdType.SHIKIMORI);
                            assertThat(dto.requestedId()).isEqualTo("20");
                            assertThat(dto.normalizedId()).isEqualTo("20");
                            assertThat(dto.embedLink())
                                    .startsWith("https://")
                                    .contains("kodikplayer.com/");
                            assertThat(dto.mediaType()).isIn("serial", "video");
                        });
    }

    @Test
    @Order(2)
    @DisplayName("Kinopoisk id resolves to a kodikplayer.com embed link")
    void kinopoiskResolves() {
        webTestClient
                .get()
                .uri("/api/v1/embed/kinopoisk/326")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(EmbedLinkDto.class)
                .value(
                        dto -> {
                            assertThat(dto.idType()).isEqualTo(KodikIdType.KINOPOISK);
                            assertThat(dto.embedLink())
                                    .startsWith("https://")
                                    .contains("kodikplayer.com/");
                        });
    }

    @Test
    @Order(3)
    @DisplayName(
            "Imdb id without tt prefix is auto-normalised and resolves to a kodikplayer.com link")
    void imdbBareIdNormalised() {
        webTestClient
                .get()
                .uri("/api/v1/embed/imdb/0903747")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(EmbedLinkDto.class)
                .value(
                        dto -> {
                            assertThat(dto.requestedId()).isEqualTo("0903747");
                            assertThat(dto.normalizedId()).isEqualTo("tt0903747");
                            assertThat(dto.embedLink())
                                    .startsWith("https://")
                                    .contains("kodikplayer.com/");
                        });
    }

    @Test
    @Order(4)
    @DisplayName("Unknown idType returns HTTP 400 without hitting Kodik")
    void unknownIdType() {
        webTestClient
                .get()
                .uri("/api/v1/embed/anilist/20")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @Order(5)
    @DisplayName("Implausible numeric id returns HTTP 404 (Kodik 'found': false)")
    void unknownIdReturnsNotFound() {
        webTestClient
                .get()
                .uri("/api/v1/embed/shikimori/9999999999")
                .exchange()
                .expectStatus()
                .isNotFound();
    }
}
