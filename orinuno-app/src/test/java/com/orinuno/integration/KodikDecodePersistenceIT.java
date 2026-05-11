/*
 * KodikDecodePersistenceIT — ADR 0018 Phase 0.4 invariant.
 *
 * Locks the post-Phase-0.4c contract: kodik_episode_variant is L1-only, every decoded URL
 * lives in episode_video, and the EpisodeVariantRepository JOIN-backed queries
 * (findByIdWithDecodedVideo, findByContentIdWithDecodedVideo, findByContentIdWithoutMp4)
 * read episode_video as the source of truth. The backfill migration
 * (20260511000000_backfill_episode_video_from_kodik_variant.sql) runs before the drop
 * (20260511010000_drop_kodik_variant_l2_columns.sql) on legacy databases; on a fresh DB it
 * is a no-op against an empty table. The drop migration is verified here by asserting the
 * column-absent state via INFORMATION_SCHEMA.
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
     * without this hook each test would see leftovers from its predecessors.
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
     * Seeds a kodik_content row (id=42) and a single kodik_episode_variant row (without any of the
     * dropped L2 columns) keyed on (content=42, season=1, episode=1, translation=610). Returns the
     * auto-generated variant id.
     */
    private long seedContentAndVariant() {
        jdbc.update(
                "INSERT INTO kodik_content (id, kodik_id, type, title, year)"
                        + " VALUES (?, ?, ?, ?, ?)",
                42L,
                "ksid-42",
                "anime",
                "Phase 0.4c Title",
                2024);
        jdbc.update(
                "INSERT INTO kodik_episode_variant"
                        + " (content_id, season_number, episode_number, translation_id,"
                        + " translation_title, translation_type, quality, kodik_link)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                42L,
                1,
                1,
                610,
                "AniDUB",
                "voice",
                "720",
                "https://kodik.info/seria/p04c/720p");
        return jdbc.queryForObject(
                "SELECT id FROM kodik_episode_variant WHERE content_id = 42", Long.class);
    }

    /**
     * Mirrors a decode into episode_source + episode_video for the seeded variant. Returns the
     * episode_video.id of the freshly-inserted row.
     */
    private long mirrorDecodedVideo(String videoUrl, LocalDateTime decodedAt) {
        jdbc.update(
                "INSERT INTO episode_source"
                        + " (content_id, season, episode, translator_id, translator_name, provider,"
                        + " source_url, discovered_at, last_seen_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                42L,
                1,
                1,
                "610",
                "AniDUB",
                "KODIK",
                "https://kodik.info/seria/p04c/720p",
                Timestamp.valueOf(decodedAt),
                Timestamp.valueOf(decodedAt));
        Long sourceId =
                jdbc.queryForObject(
                        "SELECT id FROM episode_source WHERE content_id = 42 AND provider ="
                                + " 'KODIK'",
                        Long.class);
        jdbc.update(
                "INSERT INTO episode_video"
                        + " (source_id, quality, video_url, decoded_at, decode_method,"
                        + " decode_failed_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                sourceId,
                "720",
                videoUrl,
                Timestamp.valueOf(decodedAt),
                "REGEX",
                0);
        return jdbc.queryForObject(
                "SELECT id FROM episode_video WHERE source_id = ?", Long.class, sourceId);
    }

    @Test
    @DisplayName(
            "kodik_episode_variant is L1-only after Phase 0.4c — mp4_link / mp4_link_decoded_at /"
                    + " decode_method columns are dropped")
    void variantSchemaHasNoLegacyL2Columns() {
        List<String> columns =
                jdbc.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE()"
                                + " AND TABLE_NAME = 'kodik_episode_variant'",
                        String.class);
        assertThat(columns)
                .as(
                        "Phase 0.4c drop migration must remove the three L2 columns from the L1"
                                + " variant table")
                .doesNotContain("mp4_link", "mp4_link_decoded_at", "decode_method");
        assertThat(columns)
                .as("L1 columns survive")
                .contains("id", "content_id", "season_number", "kodik_link", "local_filepath");
    }

    @Test
    @DisplayName(
            "findByContentIdWithDecodedVideo INNER-JOINs episode_video and populates mp4Link /"
                    + " decodeMethod / mp4LinkDecodedAt from the joined columns")
    void findByContentIdWithDecodedVideoPopulatesMp4LinkFromJoin() {
        seedContentAndVariant();
        LocalDateTime decodedAt = LocalDateTime.now().withNano(0);
        mirrorDecodedVideo("https://cdn.example.com/p04c.mp4", decodedAt);

        List<KodikEpisodeVariant> variants = variantRepository.findByContentIdWithDecodedVideo(42L);
        assertThat(variants).hasSize(1);
        KodikEpisodeVariant variant = variants.get(0);
        assertThat(variant.getContentId()).isEqualTo(42L);
        assertThat(variant.getMp4Link()).isEqualTo("https://cdn.example.com/p04c.mp4");
        assertThat(variant.getDecodeMethod()).isEqualTo("REGEX");
        assertThat(variant.getMp4LinkDecodedAt()).isEqualTo(decodedAt);
    }

    @Test
    @DisplayName(
            "findByContentIdWithDecodedVideo returns empty when no episode_video row exists for"
                    + " the variant (variant lives but is not yet decoded)")
    void findByContentIdWithDecodedVideoReturnsEmptyWhenNoVideo() {
        seedContentAndVariant();
        List<KodikEpisodeVariant> variants = variantRepository.findByContentIdWithDecodedVideo(42L);
        assertThat(variants)
                .as("INNER JOIN filters out variants that have no decoded video row")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "findByIdWithDecodedVideo returns Optional.empty until a populated episode_video row"
                    + " arrives, then carries mp4Link populated from the joined column")
    void findByIdWithDecodedVideoFollowsEpisodeVideoLifecycle() {
        long variantId = seedContentAndVariant();
        assertThat(variantRepository.findByIdWithDecodedVideo(variantId)).isEmpty();

        LocalDateTime decodedAt = LocalDateTime.now().withNano(0);
        mirrorDecodedVideo("https://cdn.example.com/fresh.mp4", decodedAt);

        KodikEpisodeVariant variant =
                variantRepository.findByIdWithDecodedVideo(variantId).orElseThrow();
        assertThat(variant.getMp4Link()).isEqualTo("https://cdn.example.com/fresh.mp4");
        assertThat(variant.getDecodeMethod()).isEqualTo("REGEX");
    }

    @Test
    @DisplayName(
            "findByContentIdWithoutMp4 returns variants whose episode_video row is missing or"
                    + " carries a null video_url — the inverse of findByContentIdWithDecodedVideo")
    void findByContentIdWithoutMp4InverseOfDecodedQuery() {
        seedContentAndVariant();

        // No episode_video row yet — variant must surface as pending.
        List<KodikEpisodeVariant> pending = variantRepository.findByContentIdWithoutMp4(42L);
        assertThat(pending).hasSize(1);

        // After mirror, variant must drop out of the pending set.
        mirrorDecodedVideo("https://cdn.example.com/mirror.mp4", LocalDateTime.now().withNano(0));
        assertThat(variantRepository.findByContentIdWithoutMp4(42L)).isEmpty();
    }
}
