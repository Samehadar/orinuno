package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.model.dto.ParseRequestDto;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Live integration test that calls the real Kodik API. Requires KODIK_TOKEN environment variable to
 * be set. Skipped automatically in CI if token is not provided.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "60s")
@EnabledIfEnvironmentVariable(named = "KODIK_TOKEN", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KodikLiveIntegrationTest {

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
                                java.nio.file.Files.createTempDirectory("kodik-tokens-it-");
                        return dir.resolve("kodik_tokens.json").toAbsolutePath().toString();
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        registry.add("orinuno.kodik.validate-on-startup", () -> "false");
        registry.add("orinuno.kodik.auto-discovery-enabled", () -> "false");
    }

    @Autowired private WebTestClient webTestClient;

    private static Long savedContentId;
    private static String savedKinopoiskId;

    @Test
    @Order(1)
    @DisplayName("Health endpoint should return UP")
    void healthShouldReturnUp() {
        webTestClient
                .get()
                .uri("/api/v1/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP")
                .jsonPath("$.service")
                .isEqualTo("orinuno");
    }

    @Test
    @Order(2)
    @DisplayName("Search anime 'Naruto' by shikimori_id=20 should return results")
    void searchNarutoByShikimoriId() {
        ParseRequestDto request = ParseRequestDto.builder().shikimoriId("20").build();

        webTestClient
                .post()
                .uri("/api/v1/parse/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray()
                .jsonPath("$.length()")
                .value(
                        length -> {
                            assertThat((int) length).isGreaterThan(0);
                        })
                .jsonPath("$[0].id")
                .value(
                        id -> {
                            savedContentId = Long.valueOf(id.toString());
                            assertThat(savedContentId).isPositive();
                        })
                .jsonPath("$[0].title")
                .value(
                        title -> {
                            assertThat(title.toString()).isNotBlank();
                        })
                .jsonPath("$[0].kinopoiskId")
                .value(
                        kpId -> {
                            if (kpId != null) {
                                savedKinopoiskId = kpId.toString();
                            }
                        });
    }

    @Test
    @Order(3)
    @DisplayName("Content list should contain at least one entry after parse")
    void contentListShouldNotBeEmpty() {
        webTestClient
                .get()
                .uri("/api/v1/content?page=0&size=10")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .value(
                        total -> {
                            assertThat(Long.parseLong(total.toString())).isGreaterThanOrEqualTo(1);
                        })
                .jsonPath("$.content")
                .isArray()
                .jsonPath("$.content.length()")
                .value(
                        len -> {
                            assertThat((int) len).isGreaterThanOrEqualTo(1);
                        });
    }

    @Test
    @Order(4)
    @DisplayName("Content by ID should return parsed anime data")
    void getContentByIdShouldReturnData() {
        Assumptions.assumeTrue(savedContentId != null, "No content saved from previous test");

        webTestClient
                .get()
                .uri("/api/v1/content/{id}", savedContentId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(savedContentId.intValue())
                .jsonPath("$.title")
                .isNotEmpty()
                .jsonPath("$.type")
                .isNotEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("Episode variants should exist for parsed anime")
    void variantsShouldExist() {
        Assumptions.assumeTrue(savedContentId != null, "No content saved from previous test");

        webTestClient
                .get()
                .uri("/api/v1/content/{id}/variants", savedContentId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray()
                .jsonPath("$.length()")
                .value(
                        len -> {
                            assertThat((int) len).isGreaterThan(0);
                        })
                .jsonPath("$[0].translationTitle")
                .isNotEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("Export endpoint should return structured data with seasons")
    void exportShouldReturnStructuredData() {
        Assumptions.assumeTrue(savedContentId != null, "No content saved from previous test");

        webTestClient
                .get()
                .uri("/api/v1/export/{contentId}", savedContentId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(savedContentId.intValue())
                .jsonPath("$.title")
                .isNotEmpty()
                .jsonPath("$.type")
                .isNotEmpty()
                .jsonPath("$.seasons")
                .isArray();
    }

    @Test
    @Order(7)
    @DisplayName("Find content by kinopoisk_id should work")
    void findByKinopoiskIdShouldWork() {
        Assumptions.assumeTrue(savedKinopoiskId != null, "No kinopoisk_id available from search");

        webTestClient
                .get()
                .uri("/api/v1/content/by-kinopoisk/{kpId}", savedKinopoiskId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.kinopoiskId")
                .isEqualTo(savedKinopoiskId)
                .jsonPath("$.title")
                .isNotEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("Search anime 'Steins;Gate' by shikimori_id=5 should also work")
    void searchSteinsGateByShikimoriId() {
        ParseRequestDto request = ParseRequestDto.builder().shikimoriId("5").build();

        webTestClient
                .post()
                .uri("/api/v1/parse/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray()
                .jsonPath("$.length()")
                .value(
                        length -> {
                            assertThat((int) length).isGreaterThan(0);
                        });
    }

    @Test
    @Order(9)
    @DisplayName("Content list should now contain multiple entries")
    void contentListShouldHaveMultipleEntries() {
        webTestClient
                .get()
                .uri("/api/v1/content?page=0&size=50")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .value(
                        total -> {
                            assertThat(Long.parseLong(total.toString())).isGreaterThanOrEqualTo(2);
                        });
    }

    @Test
    @Order(10)
    @DisplayName("Decoder health endpoint should be accessible")
    void decoderHealthShouldBeAccessible() {
        webTestClient
                .get()
                .uri("/api/v1/health/decoder")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.successRate")
                .isNotEmpty()
                .jsonPath("$.totalDecoded")
                .isNotEmpty()
                .jsonPath("$.totalFailed")
                .isNotEmpty();
    }
}
