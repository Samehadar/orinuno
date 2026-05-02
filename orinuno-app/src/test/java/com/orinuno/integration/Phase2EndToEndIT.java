package com.orinuno.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.repository.ContentRepository;
import com.orinuno.repository.EpisodeVariantRepository;
import com.orinuno.service.ParserService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * End-to-end integration test for the Phase 2 parse-request log + export-ready flow.
 *
 * <p>Boots a full Spring context against a Testcontainers MySQL 8 instance with Liquibase
 * migrations applied. Uses the live HTTP layer (WebTestClient) and the real {@code
 * RequestWorker} @Scheduled tick to verify wiring across:
 *
 * <ul>
 *   <li>{@code POST /api/v1/parse/requests} → idempotent insert by canonical-JSON hash;
 *   <li>{@code RequestWorker.tick()} → claim → {@code ParserService.searchInternal} → {@code
 *       markDone} with {@code result_content_ids};
 *   <li>{@code GET /api/v1/parse/requests/{id}} → status/phase reporting;
 *   <li>{@code GET /api/v1/export/ready} → returns content with decoded mp4 variants.
 * </ul>
 *
 * <p>{@link ParserService} is mocked at the service boundary so the test does not call the real
 * Kodik API; the rest of the stack (HTTP, MyBatis, MySQL, Liquibase, scheduler thread pool) is
 * exercised for real.
 *
 * <p>Tagged {@code "e2e"} — excluded from default {@code mvn test} via {@code excludedGroups} in
 * surefire. Run with {@code mvn test -Dgroups=e2e} or {@code mvn test -DexcludedGroups=}.
 */
@Tag("e2e")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "orinuno.kodik.validate-on-startup=false",
            "orinuno.kodik.auto-discovery-enabled=false",
            "orinuno.kodik.bootstrap-from-env=false",
            "orinuno.kodik.token=phase2-e2e-fake-token",
            "orinuno.playwright.enabled=false",
            "orinuno.security.api-key=",
            "orinuno.requests.worker-poll-ms=200",
            "orinuno.requests.stale-recovery-ms=60000",
            "orinuno.cache.reference.enabled=false",
            "spring.liquibase.contexts=default"
        })
class Phase2EndToEndIT {

    @Container
    @SuppressWarnings(
            "resource") // Testcontainers manages lifecycle via @Testcontainers + @Container
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

    @MockitoBean private ParserService parserService;

    @Autowired private WebTestClient webClient;
    @Autowired private ContentRepository contentRepository;
    @Autowired private EpisodeVariantRepository episodeVariantRepository;

    @Test
    @DisplayName(
            "POST /parse/requests → worker tick claims → searchInternal returns content →"
                    + " request reaches DONE with result_content_ids")
    void submitFlowReachesDone() {
        AtomicLong seededId = new AtomicLong();
        when(parserService.searchInternal(any(ParseRequestDto.class), any()))
                .thenAnswer(
                        invocation -> {
                            KodikContent seed =
                                    KodikContent.builder()
                                            .kodikId("e2e-flow")
                                            .type("anime")
                                            .title("Phase2 e2e flow")
                                            .year(2026)
                                            .kinopoiskId("e2e-kp-" + System.nanoTime())
                                            .build();
                            contentRepository.insert(seed);
                            seededId.set(seed.getId());
                            return Mono.just(List.of(seed));
                        });

        Map<String, Object> created =
                webClient
                        .post()
                        .uri("/api/v1/parse/requests")
                        .header("X-Created-By", "phase2-e2e")
                        .bodyValue(Map.of("title", "Phase2 e2e", "decodeLinks", false))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(
                                new org.springframework.core.ParameterizedTypeReference<
                                        Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(created).isNotNull();
        Long requestId = ((Number) created.get("id")).longValue();
        assertThat(created.get("status")).isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(
                        () -> {
                            Map<String, Object> view =
                                    webClient
                                            .get()
                                            .uri("/api/v1/parse/requests/{id}", requestId)
                                            .exchange()
                                            .expectStatus()
                                            .isOk()
                                            .expectBody(
                                                    new org.springframework.core
                                                                    .ParameterizedTypeReference<
                                                            Map<String, Object>>() {})
                                            .returnResult()
                                            .getResponseBody();
                            assertThat(view).isNotNull();
                            assertThat(view.get("status")).isEqualTo("DONE");
                            List<?> ids = (List<?>) view.get("resultContentIds");
                            assertThat(ids).hasSize(1);
                            assertThat(((Number) ids.get(0)).longValue()).isEqualTo(seededId.get());
                        });
    }

    @Test
    @DisplayName(
            "POST /parse/requests with identical body → second call returns 200 with same id"
                    + " (idempotency by canonical-JSON SHA-256)")
    void idempotentSubmitReturnsExistingActiveRequest() {
        when(parserService.searchInternal(any(ParseRequestDto.class), any()))
                .thenReturn(Mono.never());

        Map<String, Object> body =
                Map.of(
                        "title", "Idempotency Probe",
                        "kinopoiskId", "kp-idemp-1",
                        "decodeLinks", false);

        Map<String, Object> first =
                webClient
                        .post()
                        .uri("/api/v1/parse/requests")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(
                                new org.springframework.core.ParameterizedTypeReference<
                                        Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(first).isNotNull();
        Long firstId = ((Number) first.get("id")).longValue();

        Map<String, Object> second =
                webClient
                        .post()
                        .uri("/api/v1/parse/requests")
                        .bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(
                                new org.springframework.core.ParameterizedTypeReference<
                                        Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();
        assertThat(second).isNotNull();
        Long secondId = ((Number) second.get("id")).longValue();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(first.get("requestHash")).isEqualTo(second.get("requestHash"));
    }

    @Test
    @DisplayName(
            "GET /export/ready returns content with decoded mp4 variants and exposes Phase 2"
                    + " metadata fields (lastSeason, lastEpisode, ongoing)")
    void exportReadyReturnsContentWithDecodedVariants() {
        KodikContent ready =
                KodikContent.builder()
                        .kodikId("e2e-ready")
                        .type("anime-serial")
                        .title("Phase2 export ready")
                        .titleOrig("Phase2 export ready original")
                        .year(2026)
                        .kinopoiskId("kp-export-" + System.nanoTime())
                        .lastSeason(1)
                        .lastEpisode(12)
                        .episodesCount(24)
                        .build();
        contentRepository.insert(ready);

        KodikEpisodeVariant decoded =
                KodikEpisodeVariant.builder()
                        .contentId(ready.getId())
                        .seasonNumber(1)
                        .episodeNumber(1)
                        .translationId(610)
                        .translationTitle("AniLibria")
                        .translationType("voice")
                        .quality("720p")
                        .kodikLink("//kodik.example/foo")
                        .mp4Link("https://cdn.example.com/movie-1.mp4")
                        .mp4LinkDecodedAt(LocalDateTime.now())
                        .build();
        episodeVariantRepository.insert(decoded);

        Map<String, Object> page =
                webClient
                        .get()
                        .uri("/api/v1/export/ready?page=0&size=20")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(
                                new org.springframework.core.ParameterizedTypeReference<
                                        Map<String, Object>>() {})
                        .returnResult()
                        .getResponseBody();

        assertThat(page).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("content");
        assertThat(items).isNotEmpty();

        Map<String, Object> match =
                items.stream()
                        .filter(it -> ready.getId().equals(((Number) it.get("id")).longValue()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("seeded content not in /ready page"));

        assertThat(match.get("title")).isEqualTo("Phase2 export ready");
        assertThat(match.get("lastSeason")).isEqualTo(1);
        assertThat(match.get("lastEpisode")).isEqualTo(12);
        assertThat(match.get("episodesCount")).isEqualTo(24);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seasons = (List<Map<String, Object>>) match.get("seasons");
        assertThat(seasons).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> episodes =
                (List<Map<String, Object>>) seasons.get(0).get("episodes");
        assertThat(episodes).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variants =
                (List<Map<String, Object>>) episodes.get(0).get("variants");
        assertThat(variants).hasSize(1);
        assertThat(variants.get(0).get("mp4Link")).isEqualTo("https://cdn.example.com/movie-1.mp4");
        assertThat(variants.get(0).get("translationId")).isEqualTo(610);
    }

    @Test
    @DisplayName(
            "GET /parse/requests?limit=0 returns empty rows and X-Total-Count header so"
                    + " parser-kodik can read pending-queue depth without payload")
    void listLimitZeroExposesTotalCountHeader() {
        when(parserService.searchInternal(any(ParseRequestDto.class), any()))
                .thenReturn(Mono.never());

        webClient
                .post()
                .uri("/api/v1/parse/requests")
                .bodyValue(Map.of("title", "queue-depth-probe-" + System.nanoTime()))
                .exchange()
                .expectStatus()
                .isCreated();

        webClient
                .get()
                .uri("/api/v1/parse/requests?status=PENDING&limit=0")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists("X-Total-Count")
                .expectBody(
                        new org.springframework.core.ParameterizedTypeReference<
                                List<Map<String, Object>>>() {})
                .value(rows -> assertThat(rows).isEmpty());
    }
}
