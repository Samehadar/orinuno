/*
 * B2EpisodeSourcePipelineIT — ADR 0021 Block B2 end-to-end integration test.
 *
 * Exercises the full event-ingestion pipeline against a real MySQL 8 container:
 *
 *   HttpServer stub  →  KodikRemoteEventPoller (WebClient)
 *                   →  CatalogSinkEventEmitter (Jackson deserialise)
 *                   →  CatalogIdentityResolver (canonical catalog_content row)
 *                   →  EpisodeSourceRepository (MyBatis upsert into episode_source)
 *
 * The unit test in {@link CatalogSinkEventEmitterTest} pins the emitter's
 * branching with mocks; this IT locks the SQL surface so a future refactor of
 * the MyBatis mapper / Liquibase column shape / Spring autowiring breaks here
 * before it reaches docker-compose. Matches the primer style of
 * {@code CatalogChangelogApplyIT} (Testcontainers MySQL + Liquibase apply) and
 * {@code JutsuRemoteEventPollerTest} (HttpServer-backed SourceCatalogEvent
 * payload), but bolts both together with a real Spring context.
 *
 * Tagged "e2e" — excluded from `mvn test` by surefire's default
 * {@code excludedGroups}. Run with
 *   mvn -pl meter test -Dgroups=e2e -DexcludedGroups= \
 *       -Dtest=B2EpisodeSourcePipelineIT -Dspotless.check.skip=true
 * Requires Docker on the host.
 */
package com.orinuno.meter.catalog.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.meter.Application;
import com.orinuno.meter.poller.KodikRemoteEventPoller;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("e2e")
@Testcontainers
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            // Disable @Scheduled firing during the test — we drive pollOnce() manually
            // so the assertions race nothing. 1-day delay = effectively off for the
            // duration of the IT, no extra scheduler-disable plumbing needed.
            "orinuno.source-kodik.poll-interval-ms=86400000",
            "orinuno.source-kodik.poll-initial-delay-ms=86400000",
            "orinuno.source-kodik.poll-batch-size=50"
        })
@DisplayName("ADR 0021 B2 pipeline IT — poll → emit → catalog_content + episode_source")
class B2EpisodeSourcePipelineIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno_catalog")
                    .withUsername("root")
                    .withPassword("test");

    /**
     * HttpServer must be alive before Spring's WebClient builder resolves the {@code
     * orinuno.source-kodik.base-url} property, so it is started in a class-level static initialiser
     * (rather than {@code @BeforeAll}) and torn down in {@code @AfterAll}. The handler reference is
     * mutated per-test so a single server instance can serve multiple bodies if we later add cases.
     */
    private static final HttpServer BACKEND = startBackend();

    private static final AtomicReference<HttpHandler> HANDLER = new AtomicReference<>();

    @Autowired private KodikRemoteEventPoller poller;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SourceEventDecodedController decodedController;

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(
                "orinuno.source-kodik.base-url",
                () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterAll
    static void stopBackend() {
        if (BACKEND != null) {
            BACKEND.stop(0);
        }
    }

    @BeforeEach
    void resetSchema() {
        // FK order matters: episode_video → episode_source → catalog_content,
        // external_id → catalog_content.
        jdbc.execute("DELETE FROM episode_video");
        jdbc.execute("DELETE FROM episode_source");
        jdbc.execute("DELETE FROM catalog_content_external_id");
        jdbc.execute("DELETE FROM catalog_episode_source_link");
        jdbc.execute("DELETE FROM catalog_episode");
        jdbc.execute("DELETE FROM catalog_content");
        jdbc.execute("DELETE FROM orinuno_remote_source_watermark");
    }

    @Test
    @DisplayName(
            "SeriesDiscovered (kodik) with two episodes → 1 catalog_content + 2 episode_source"
                    + " rows; watermark advances")
    void seriesDiscoveredPipelineWritesL3ChromeAndL2Sources() {
        String body =
                "[{\"kind\":\"series-discovered\","
                    + "\"identifier\":{\"sourceType\":\"kodik\",\"sourceId\":\"naruto-2002\"},"
                    + "\"info\":{\"titleRu\":\"Наруто\",\"titleEn\":\"Naruto\",\"year\":2002,"
                    + "\"kindHint\":\"ANIME\","
                    + "\"externalIds\":{\"shikimoriId\":\"1\",\"kinopoiskId\":\"283290\"}},\"seasons\":[{\"title\":\"Сезон"
                    + " 1\",\"order\":1,\"episodes\":[{\"title\":\"Эпизод"
                    + " 1\",\"duration\":\"PT24M\",\"order\":1,\"variants\":[{"
                    + "\"identifier\":{\"sourceType\":\"kodik\",\"sourceId\":\"naruto-2002:s1e1:rus\"},"
                    + "\"mediaUrl\":\"https://kodik.example/s1e1.iframe\",\"title\":\"AniLibria\","
                    + "\"streamQuality\":\"720p\",\"duration\":\"PT24M\"}]},{\"title\":\"Эпизод"
                    + " 2\",\"duration\":\"PT24M\",\"order\":2,\"variants\":[{"
                    + "\"identifier\":{\"sourceType\":\"kodik\",\"sourceId\":\"naruto-2002:s1e2:rus\"},"
                    + "\"mediaUrl\":\"https://kodik.example/s1e2.iframe\",\"title\":\"AniLibria\","
                    + "\"streamQuality\":\"720p\",\"duration\":\"PT24M\"}]}]}],\"provenance\":{"
                    + "\"sourceUrl\":\"https://kodik-api.com/list\","
                    + "\"fetchedAt\":\"2026-05-12T08:00:00Z\"}}]";
        HANDLER.set(exchange -> sendJson(exchange, 200, body));

        poller.pollOnce();

        List<Map<String, Object>> contents =
                jdbc.queryForList(
                        "SELECT id, title_ru, title_en, year, shikimori_id, kinopoisk_id"
                                + " FROM catalog_content");
        assertThat(contents).as("one canonical catalog_content row per source title").hasSize(1);
        Map<String, Object> content = contents.get(0);
        assertThat(content.get("title_ru")).isEqualTo("Наруто");
        assertThat(content.get("title_en")).isEqualTo("Naruto");
        assertThat(((Number) content.get("year")).intValue()).isEqualTo(2002);
        assertThat(content.get("shikimori_id")).isEqualTo("1");
        assertThat(content.get("kinopoisk_id")).isEqualTo("283290");

        long contentId = ((Number) content.get("id")).longValue();

        List<Map<String, Object>> sources =
                jdbc.queryForList(
                        "SELECT season, episode, translator_id, translator_name, provider,"
                                + " source_url, source_type FROM episode_source WHERE content_id=?"
                                + " ORDER BY episode",
                        contentId);
        assertThat(sources).as("one episode_source row per variant").hasSize(2);

        Map<String, Object> ep1 = sources.get(0);
        assertThat(ep1)
                .containsEntry("season", 1)
                .containsEntry("episode", 1)
                .containsEntry("translator_id", "naruto-2002:s1e1:rus")
                .containsEntry("translator_name", "AniLibria")
                .containsEntry("provider", "KODIK")
                .containsEntry("source_url", "https://kodik.example/s1e1.iframe")
                .containsEntry("source_type", "KODIK");

        Map<String, Object> ep2 = sources.get(1);
        assertThat(ep2)
                .containsEntry("season", 1)
                .containsEntry("episode", 2)
                .containsEntry("translator_id", "naruto-2002:s1e2:rus")
                .containsEntry("source_url", "https://kodik.example/s1e2.iframe");

        // Watermark must advance to the event's fetchedAt — locks the poller side of B2.
        List<Map<String, Object>> watermark =
                jdbc.queryForList(
                        "SELECT source_type, last_fetched_at, last_event_count, last_error FROM"
                                + " orinuno_remote_source_watermark WHERE source_type='kodik'");
        assertThat(watermark).hasSize(1);
        assertThat(((Number) watermark.get(0).get("last_event_count")).intValue()).isEqualTo(1);
        assertThat(watermark.get(0).get("last_error")).isNull();
        assertThat((LocalDateTime) watermark.get(0).get("last_fetched_at"))
                .isEqualTo(LocalDateTime.of(2026, 5, 12, 8, 0));
    }

    @Test
    @DisplayName(
            "VariantDecoded (kodik) routed through SourceEventDecodedController writes one"
                    + " episode_video row keyed by the resolved episode_source.id (ADR 0021"
                    + " B2-decoded)")
    void variantDecodedPipelineWritesEpisodeVideo() {
        // Seed the canonical row + episode_source row via the poller path (same shape as the
        // first test). Without this seed, the decoded handler logs WARN + drops — verified
        // separately in CatalogSinkEventEmitterTest.variantDecodedDropsWhenSourceRowMissing.
        String seedBody =
                "[{\"kind\":\"series-discovered\","
                    + "\"identifier\":{\"sourceType\":\"kodik\",\"sourceId\":\"naruto-2002\"},"
                    + "\"info\":{\"titleRu\":\"Наруто\",\"kindHint\":\"ANIME\",\"externalIds\":{}},"
                    + "\"seasons\":[{\"order\":1,\"episodes\":[{\"order\":1,\"variants\":[{"
                    + "\"identifier\":{\"sourceType\":\"kodik\",\"sourceId\":\"naruto-2002:s1e1:rus\"},"
                    + "\"mediaUrl\":\"https://kodik.example/s1e1.iframe\","
                    + "\"streamQuality\":\"720p\"}]}]}],"
                    + "\"provenance\":{\"sourceUrl\":\"https://kodik-api.com/list\","
                    + "\"fetchedAt\":\"2026-05-12T08:00:00Z\"}}]";
        HANDLER.set(exchange -> sendJson(exchange, 200, seedBody));
        poller.pollOnce();

        // Sanity: the seed produced one episode_source row.
        Long contentId =
                jdbc.queryForObject(
                        "SELECT id FROM catalog_content WHERE title_ru='Наруто'", Long.class);
        Long sourceId =
                jdbc.queryForObject(
                        "SELECT id FROM episode_source WHERE content_id=? AND season=1 AND"
                                + " episode=1 AND translator_id='naruto-2002:s1e1:rus' AND"
                                + " provider='KODIK'",
                        Long.class,
                        contentId);
        assertThat(sourceId).as("seed must produce an episode_source row").isNotNull();

        // Now drive the decoded-event path directly through the controller bean (no need to
        // start a Spring web server — we want SQL-side coverage, not transport coverage; the
        // wire-format pinning lives in MeterDecodedEventPublisherTest on the producer side).
        SourceCatalogEvent.VariantDecoded decoded =
                new SourceCatalogEvent.VariantDecoded(
                        SourceIdentifier.of("kodik", "naruto-2002"),
                        1,
                        1,
                        SourceIdentifier.of("kodik", "naruto-2002:s1e1:rus"),
                        "https://cdn.kodik.example/naruto/s1e1-720.m3u8",
                        "720",
                        "REGEX",
                        3600,
                        Provenance.of(
                                "https://kodik.example/s1e1.iframe",
                                Instant.parse("2026-05-12T08:10:00Z")));
        decodedController.ingest(List.of(decoded));

        List<Map<String, Object>> videos =
                jdbc.queryForList(
                        "SELECT source_id, quality, video_url, video_format, decode_method,"
                                + " ttl_seconds, decode_failed_count FROM episode_video WHERE"
                                + " source_id=?",
                        sourceId);
        assertThat(videos).hasSize(1);
        Map<String, Object> video = videos.get(0);
        assertThat(((Number) video.get("source_id")).longValue()).isEqualTo(sourceId);
        assertThat(video.get("quality")).isEqualTo("720");
        assertThat(video.get("video_url"))
                .isEqualTo("https://cdn.kodik.example/naruto/s1e1-720.m3u8");
        assertThat(video.get("video_format")).isEqualTo("application/x-mpegURL");
        assertThat(video.get("decode_method")).isEqualTo("REGEX");
        assertThat(((Number) video.get("ttl_seconds")).intValue()).isEqualTo(3600);
        assertThat(((Number) video.get("decode_failed_count")).intValue()).isEqualTo(0);
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/v1/source-events/ready",
                    exchange -> {
                        HttpHandler current = HANDLER.get();
                        if (current == null) {
                            sendJson(exchange, 200, "[]");
                        } else {
                            current.handle(exchange);
                        }
                    });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub HttpServer", e);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
