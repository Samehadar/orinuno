/*
 * KodikDecodePersistenceIT — ADR 0018 Phase 0.4 invariant.
 *
 * Locks the contract behind the kodik_episode_variant → episode_source + episode_video
 * dual-write (Phase 0.4a, this commit) and the read-path migration (Phase 0.4b) so that
 * dropping the L2 columns in Phase 0.4c cannot regress the decoded-URL persistence path.
 *
 * Scenarios:
 *   1. Backfill idempotency — re-running 20260511000000_backfill_episode_video_from_kodik_variant
 *      against a fresh DB with no kodik_episode_variant rows is a no-op (no episode_video rows).
 *   2. Backfill completion — given a kodik_episode_variant row with mp4_link populated but no
 *      mirrored episode_video row, re-running the backfill INSERTs the matching episode_source +
 *      episode_video rows. Running it again is a no-op (ON DUPLICATE KEY UPDATE).
 *
 * Tagged "e2e" — Testcontainers MySQL is slow. Run with mvn test -Pe2e
 * -Dtest=KodikDecodePersistenceIT.
 */
package com.orinuno.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.repository.EpisodeVariantRepository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("e2e")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            "orinuno.kodik.validate-on-startup=false",
            "orinuno.kodik.auto-discovery-enabled=false",
            "orinuno.kodik.bootstrap-from-env=false",
            "orinuno.kodik.token=decode-it-fake-token",
            "orinuno.playwright.enabled=false",
            "orinuno.security.api-key=",
            "orinuno.cache.reference.enabled=false",
            "spring.liquibase.contexts=default"
        })
class KodikDecodePersistenceIT {

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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EpisodeVariantRepository variantRepository;

    /**
     * Wipe the tables under test before each method. Spring shares the application context (and
     * therefore the Testcontainers MySQL instance) across @Test methods within the class, so
     * without this hook each test would see leftovers from its predecessors. FK cascades
     * (kodik_episode_variant → kodik_content, episode_video → episode_source) mean we only need to
     * delete the roots.
     */
    @BeforeEach
    void resetTables() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        jdbc.execute("TRUNCATE TABLE episode_video");
        jdbc.execute("TRUNCATE TABLE episode_source");
        jdbc.execute("TRUNCATE TABLE kodik_episode_variant");
        jdbc.execute("TRUNCATE TABLE kodik_content");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    /**
     * Mirrors the exact SQL shipped in
     * 20260511000000_backfill_episode_video_from_kodik_variant.sql. Re-runs the backfill bypassing
     * the Liquibase DATABASECHANGELOG tracking, which already recorded the first execution at
     * startup against an empty kodik_episode_variant table. Kept inline (not loaded from the
     * classpath) so an unintended drift between the migration file and what the test asserts fails
     * this test loudly.
     */
    private void runBackfill() {
        jdbc.execute(
                "INSERT INTO episode_source    (content_id, season, episode, translator_id,"
                    + " translator_name,     provider, source_url, discovered_at, last_seen_at)"
                    + " SELECT v.content_id, v.season_number, v.episode_number,      "
                    + " CAST(v.translation_id AS CHAR) COLLATE utf8mb4_unicode_ci,"
                    + " v.translation_title,       'KODIK', COALESCE(v.kodik_link, v.mp4_link),    "
                    + "   COALESCE(v.created_at, NOW()), COALESCE(v.updated_at, NOW()) FROM"
                    + " kodik_episode_variant v WHERE v.mp4_link IS NOT NULL ON DUPLICATE KEY"
                    + " UPDATE last_seen_at = GREATEST(episode_source.last_seen_at,"
                    + " COALESCE(VALUES(last_seen_at), episode_source.last_seen_at))");
        jdbc.execute(
                "INSERT INTO episode_video    (source_id, quality, video_url, decoded_at,"
                    + " decode_method,     decode_failed_count) SELECT es.id, COALESCE(v.quality,"
                    + " 'unknown'), v.mp4_link,       v.mp4_link_decoded_at, v.decode_method, 0"
                    + " FROM kodik_episode_variant v INNER JOIN episode_source es ON es.content_id"
                    + " = v.content_id AND es.season = v.season_number AND es.episode ="
                    + " v.episode_number AND es.translator_id = CAST(v.translation_id AS CHAR)"
                    + " COLLATE utf8mb4_unicode_ci AND es.provider = 'KODIK' WHERE v.mp4_link IS"
                    + " NOT NULL ON DUPLICATE KEY UPDATE video_url = COALESCE(VALUES(video_url),"
                    + " episode_video.video_url), decoded_at = COALESCE(VALUES(decoded_at),"
                    + " episode_video.decoded_at), decode_method = COALESCE(VALUES(decode_method),"
                    + " episode_video.decode_method)");
    }

    @Test
    @DisplayName("backfill is a no-op on a database with no Kodik variants carrying mp4_link")
    void backfillIsNoOpOnCleanDatabase() {
        // Liquibase already ran the backfill at startup against an empty kodik_episode_variant.
        // Re-running it must remain idempotent and create nothing.
        runBackfill();
        Integer videos = jdbc.queryForObject("SELECT COUNT(*) FROM episode_video", Integer.class);
        Integer sources = jdbc.queryForObject("SELECT COUNT(*) FROM episode_source", Integer.class);
        assertThat(videos).as("no episode_video rows on a clean DB").isZero();
        assertThat(sources).as("no episode_source rows on a clean DB").isZero();
    }

    @Test
    @DisplayName(
            "backfill mirrors a legacy kodik_episode_variant.mp4_link into episode_source +"
                    + " episode_video and stays idempotent on re-run")
    void backfillMirrorsLegacyVariantIntoNewSchema() {
        // Seed a parent kodik_content row so the FK from kodik_episode_variant succeeds.
        jdbc.update(
                "INSERT INTO kodik_content (id, kodik_id, type, title, year)"
                        + " VALUES (?, ?, ?, ?, ?)",
                1L,
                "ksid-1",
                "anime",
                "Test Title",
                2024);

        // Insert a "legacy" kodik_episode_variant row with mp4_link populated. This simulates
        // a row decoded before KodikEpisodeDualWriteService landed (or one whose dual-write
        // mirror failed silently).
        LocalDateTime decodedAt = LocalDateTime.now().withNano(0);
        jdbc.update(
                "INSERT INTO kodik_episode_variant"
                        + " (content_id, season_number, episode_number, translation_id,"
                        + " translation_title, translation_type, quality, kodik_link, mp4_link,"
                        + " mp4_link_decoded_at, decode_method)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L,
                1,
                1,
                610,
                "AniDUB",
                "voice",
                "720",
                "https://kodik.info/seria/abc/720p",
                "https://cdn.example.com/legacy.mp4",
                Timestamp.valueOf(decodedAt),
                "REGEX");

        // Pre-condition: no mirror yet (Liquibase backfill ran before this insert).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM episode_video", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM episode_source", Integer.class))
                .isZero();

        // First backfill pass — must create both rows.
        runBackfill();

        List<Map<String, Object>> sources = jdbc.queryForList("SELECT * FROM episode_source");
        assertThat(sources).hasSize(1);
        Map<String, Object> source = sources.get(0);
        assertThat(source).containsEntry("content_id", 1L);
        assertThat(source).containsEntry("season", 1);
        assertThat(source).containsEntry("episode", 1);
        assertThat(source).containsEntry("translator_id", "610");
        assertThat(source).containsEntry("provider", "KODIK");
        assertThat(source).containsEntry("source_url", "https://kodik.info/seria/abc/720p");

        List<Map<String, Object>> videos = jdbc.queryForList("SELECT * FROM episode_video");
        assertThat(videos).hasSize(1);
        Map<String, Object> video = videos.get(0);
        assertThat(video).containsEntry("quality", "720");
        assertThat(video).containsEntry("video_url", "https://cdn.example.com/legacy.mp4");
        assertThat(video).containsEntry("decode_method", "REGEX");
        // decoded_at preserved from the legacy row. JDBC driver returns the DATETIME column
        // as either Timestamp or LocalDateTime depending on MySQL connector mode; both are
        // accepted here.
        Object decodedAtValue = video.get("decoded_at");
        LocalDateTime actualDecodedAt =
                decodedAtValue instanceof Timestamp ts
                        ? ts.toLocalDateTime()
                        : (LocalDateTime) decodedAtValue;
        assertThat(actualDecodedAt).isEqualTo(decodedAt);

        // Second backfill pass — must be a no-op (ON DUPLICATE KEY UPDATE on identical input).
        runBackfill();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM episode_source", Integer.class))
                .as("second backfill must not duplicate episode_source")
                .isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM episode_video", Integer.class))
                .as("second backfill must not duplicate episode_video")
                .isOne();
    }

    @Test
    @DisplayName(
            "ADR 0018 Phase 0.4b — pending-decode predicate now reads episode_video, not"
                    + " kodik_episode_variant.mp4_link")
    void pendingDecodeReadsEpisodeVideoNotVariantMp4Link() {
        // Seed parent content row.
        jdbc.update(
                "INSERT INTO kodik_content (id, kodik_id, type, title, year)"
                        + " VALUES (?, ?, ?, ?, ?)",
                42L,
                "ksid-42",
                "anime",
                "Phase 0.4b Title",
                2024);

        // Insert a variant with a *populated* mp4_link but NO mirrored episode_video row.
        // Pre-0.4b this row would be filtered out as "already decoded". Post-0.4b it must
        // surface as "needs decode" because the source of truth is episode_video.video_url.
        jdbc.update(
                "INSERT INTO kodik_episode_variant"
                        + " (content_id, season_number, episode_number, translation_id,"
                        + " translation_title, translation_type, quality, kodik_link, mp4_link)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                42L,
                1,
                1,
                610,
                "AniDUB",
                "voice",
                "720",
                "https://kodik.info/seria/p04b/720p",
                "https://cdn.example.com/stale-but-present.mp4");

        List<KodikEpisodeVariant> pendingBeforeMirror =
                variantRepository.findByContentIdWithoutMp4(42L);
        assertThat(pendingBeforeMirror)
                .as(
                        "variant with mp4_link but no episode_video row must still be reported as"
                                + " pending decode")
                .hasSize(1);
        assertThat(pendingBeforeMirror.get(0).getMp4Link())
                .isEqualTo("https://cdn.example.com/stale-but-present.mp4");

        // Now mirror into episode_video — query must drop the row from the pending set.
        runBackfill();

        List<KodikEpisodeVariant> pendingAfterMirror =
                variantRepository.findByContentIdWithoutMp4(42L);
        assertThat(pendingAfterMirror)
                .as("once episode_video has a populated video_url the variant exits pending")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "ADR 0018 Phase 0.4b — findExpiredLinks reads episode_video.decoded_at, not"
                    + " kodik_episode_variant.mp4_link_decoded_at")
    void expiredDecodeReadsEpisodeVideo() {
        jdbc.update(
                "INSERT INTO kodik_content (id, kodik_id, type, title, year)"
                        + " VALUES (?, ?, ?, ?, ?)",
                73L,
                "ksid-73",
                "anime",
                "Phase 0.4b Expired",
                2024);

        // Stale decoded_at on the variant column, but freshly decoded in episode_video. The
        // new query joins on episode_video.decoded_at, so this row must NOT be flagged as
        // expired even though the legacy variant column suggests it is.
        LocalDateTime fresh = LocalDateTime.now().minusMinutes(5).withNano(0);
        LocalDateTime stale = LocalDateTime.now().minusDays(2).withNano(0);
        jdbc.update(
                "INSERT INTO kodik_episode_variant"
                        + " (content_id, season_number, episode_number, translation_id,"
                        + " translation_title, translation_type, quality, kodik_link, mp4_link,"
                        + " mp4_link_decoded_at, decode_method)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                73L,
                1,
                1,
                610,
                "AniDUB",
                "voice",
                "720",
                "https://kodik.info/seria/p04b-exp/720p",
                "https://cdn.example.com/fresh.mp4",
                Timestamp.valueOf(stale),
                "REGEX");

        // Backfill — but then update episode_video.decoded_at to fresh, leaving variant.
        runBackfill();
        jdbc.update(
                "UPDATE episode_video SET decoded_at = ? WHERE video_url = ?",
                Timestamp.valueOf(fresh),
                "https://cdn.example.com/fresh.mp4");

        // 24h threshold — variant column says stale (>24h old) but episode_video says fresh.
        // Query joins on episode_video.decoded_at, so the row must NOT be returned.
        List<KodikEpisodeVariant> expired = variantRepository.findExpiredLinks(24, 100);
        assertThat(expired)
                .as("episode_video.decoded_at is fresh, so the row is not expired")
                .isEmpty();
    }
}
