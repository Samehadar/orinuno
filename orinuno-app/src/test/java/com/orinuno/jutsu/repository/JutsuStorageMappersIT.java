package com.orinuno.jutsu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuSyncState;
import com.orinuno.jutsu.model.JutsuTitle;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase2-style Testcontainers integration test for the jut.su L1 mappers (ARCH-0016 P1a).
 *
 * <p>Boots a MySQL 8 container, runs Liquibase against it (so the migrations under test get applied
 * for real, including the {@code CHECK} constraint on {@code jutsu_sync_state}), and exercises the
 * three repositories at the SQL boundary. The point isn't to test every query — it's to assert the
 * contract Step 2 will rely on:
 *
 * <ul>
 *   <li>{@code jutsu_title} upsert is idempotent and {@code COALESCE}-protects {@code
 *       first_seen_at} + the catalog-only / info-only column groups (so a catalog refresh doesn't
 *       blank info-page data and vice versa);
 *   <li>{@code jutsu_episode} bulk-upsert is composite-key idempotent and {@code discovered_at} is
 *       preserved across re-fetches;
 *   <li>{@code jutsu_sync_state} initialises as a singleton, surives concurrent {@code
 *       initIfAbsent} calls (via {@code INSERT IGNORE}), and updates round-trip.
 * </ul>
 *
 * <p>Tagged {@code "e2e"} — excluded from default {@code mvn test} via {@code excludedGroups} in
 * surefire. Run with {@code mvn -pl orinuno-app test -Pe2e -Dtest=JutsuStorageMappersIT}.
 */
@Tag("e2e")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            "orinuno.kodik.validate-on-startup=false",
            "orinuno.kodik.auto-discovery-enabled=false",
            "orinuno.kodik.bootstrap-from-env=false",
            "orinuno.kodik.token=jutsu-l1-fake-token",
            "orinuno.playwright.enabled=false",
            "orinuno.security.api-key=",
            "orinuno.cache.reference.enabled=false",
            "spring.liquibase.contexts=default"
        })
class JutsuStorageMappersIT {

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

    @Autowired private JutsuTitleRepository titleRepository;
    @Autowired private JutsuEpisodeRepository episodeRepository;
    @Autowired private JutsuSyncStateRepository syncStateRepository;

    @Test
    @DisplayName(
            "jutsu_title upsert is idempotent and COALESCE-protects first_seen_at + catalog/info"
                    + " column groups across refresh waves")
    void titleUpsertIsIdempotentAndProtectsCoalescedColumns() {
        LocalDateTime t0 = LocalDateTime.now().withNano(0);

        // Wave 1: catalog crawl populates the row with catalog-only fields.
        JutsuTitle catalogOnly =
                JutsuTitle.builder()
                        .slug("naruto-test")
                        .siteId(123)
                        .title("Наруто")
                        .yearBucket("before2000")
                        .genresCsv("action,adventure")
                        .typesCsv("shonen")
                        .catalogEpisodeCount(220)
                        .catalogMovieCount(0)
                        .catalogFetchedAt(t0)
                        .firstSeenAt(t0)
                        .lastSeenAt(t0)
                        .build();
        titleRepository.upsert(catalogOnly);

        Optional<JutsuTitle> afterWave1 = titleRepository.findBySlug("naruto-test");
        assertThat(afterWave1).isPresent();
        assertThat(afterWave1.get().getCatalogEpisodeCount()).isEqualTo(220);
        assertThat(afterWave1.get().getInfoTotalEpisodes()).isNull();
        assertThat(afterWave1.get().getFirstSeenAt()).isEqualTo(t0);

        // Wave 2: info-page fetch fills in synopsis + per-season totals; catalog-only fields
        // must not be wiped because the info row legitimately doesn't carry them.
        LocalDateTime t1 = t0.plusMinutes(5);
        JutsuTitle infoOnly =
                JutsuTitle.builder()
                        .slug("naruto-test")
                        .title("Наруто") // info wave still carries title — overwrite is fine
                        .synopsis("A long-running ninja saga")
                        .infoTotalSeasons(2)
                        .infoTotalEpisodes(220)
                        .infoFetchedAt(t1)
                        // .firstSeenAt deliberately omitted to assert COALESCE protection
                        .firstSeenAt(t1) // mapper will COALESCE to keep t0
                        .lastSeenAt(t1)
                        .build();
        titleRepository.upsert(infoOnly);

        JutsuTitle merged = titleRepository.findBySlug("naruto-test").orElseThrow();
        assertThat(merged.getCatalogEpisodeCount()).isEqualTo(220); // preserved from wave 1
        assertThat(merged.getInfoTotalSeasons()).isEqualTo(2); // added by wave 2
        assertThat(merged.getSynopsis()).isEqualTo("A long-running ninja saga");
        assertThat(merged.getFirstSeenAt()).isEqualTo(t0); // first_seen_at frozen
        assertThat(merged.getLastSeenAt()).isEqualTo(t1); // last_seen_at advances
        assertThat(merged.getGenresCsv()).isEqualTo("action,adventure"); // preserved
    }

    @Test
    @DisplayName(
            "jutsu_episode bulk-upsert is composite-key idempotent; discovered_at is preserved"
                    + " on re-fetch while last_seen_at advances")
    void episodeBulkUpsertPreservesDiscoveredAt() {
        LocalDateTime t0 = LocalDateTime.now().withNano(0).minusHours(1);
        titleRepository.upsert(
                JutsuTitle.builder()
                        .slug("opm-test")
                        .title("OPM")
                        .firstSeenAt(t0)
                        .lastSeenAt(t0)
                        .build());

        List<JutsuEpisode> firstWave =
                List.of(
                        JutsuEpisode.builder()
                                .slug("opm-test")
                                .season(1)
                                .episode(1)
                                .label("1 серия")
                                .relativeUrl("/opm-test/episode-1.html")
                                .discoveredAt(t0)
                                .lastSeenAt(t0)
                                .build(),
                        JutsuEpisode.builder()
                                .slug("opm-test")
                                .season(1)
                                .episode(2)
                                .label("2 серия")
                                .relativeUrl("/opm-test/episode-2.html")
                                .discoveredAt(t0)
                                .lastSeenAt(t0)
                                .build());
        episodeRepository.upsertAll(firstWave);

        assertThat(episodeRepository.countBySlug("opm-test")).isEqualTo(2);

        LocalDateTime t1 = t0.plusMinutes(30);
        List<JutsuEpisode> secondWave =
                List.of(
                        JutsuEpisode.builder()
                                .slug("opm-test")
                                .season(1)
                                .episode(1)
                                .label("1 серия (updated)")
                                .relativeUrl("/opm-test/episode-1.html")
                                .discoveredAt(t1) // mapper must COALESCE to keep t0
                                .lastSeenAt(t1)
                                .build());
        episodeRepository.upsertAll(secondWave);

        List<JutsuEpisode> after = episodeRepository.findBySlug("opm-test");
        assertThat(after).hasSize(2);
        JutsuEpisode ep1 =
                after.stream().filter(e -> e.getEpisode() == 1).findFirst().orElseThrow();
        assertThat(ep1.getLabel()).isEqualTo("1 серия (updated)");
        assertThat(ep1.getDiscoveredAt()).isEqualTo(t0); // preserved
        assertThat(ep1.getLastSeenAt()).isEqualTo(t1); // advanced
    }

    @Test
    @DisplayName(
            "jutsu_episode.deleteMissing evicts rows whose (season, episode) tuple is absent"
                    + " from the keep-list (used after a fresh info-page parse)")
    void episodeDeleteMissingEvictsAbsentTuples() {
        LocalDateTime t0 = LocalDateTime.now().withNano(0);
        titleRepository.upsert(
                JutsuTitle.builder()
                        .slug("evict-test")
                        .title("evict")
                        .firstSeenAt(t0)
                        .lastSeenAt(t0)
                        .build());
        episodeRepository.upsertAll(
                List.of(
                        JutsuEpisode.builder()
                                .slug("evict-test")
                                .season(1)
                                .episode(1)
                                .label("e1")
                                .relativeUrl("/evict-test/episode-1.html")
                                .discoveredAt(t0)
                                .lastSeenAt(t0)
                                .build(),
                        JutsuEpisode.builder()
                                .slug("evict-test")
                                .season(1)
                                .episode(2)
                                .label("e2")
                                .relativeUrl("/evict-test/episode-2.html")
                                .discoveredAt(t0)
                                .lastSeenAt(t0)
                                .build(),
                        JutsuEpisode.builder()
                                .slug("evict-test")
                                .season(1)
                                .episode(3)
                                .label("e3")
                                .relativeUrl("/evict-test/episode-3.html")
                                .discoveredAt(t0)
                                .lastSeenAt(t0)
                                .build()));

        // Re-fetch returns only e1, e2 — e3 was pulled / consolidated upstream.
        episodeRepository.deleteMissing(
                "evict-test",
                List.of(
                        JutsuEpisode.builder().slug("evict-test").season(1).episode(1).build(),
                        JutsuEpisode.builder().slug("evict-test").season(1).episode(2).build()));

        assertThat(episodeRepository.countBySlug("evict-test")).isEqualTo(2);
        assertThat(episodeRepository.findBySlug("evict-test"))
                .extracting(JutsuEpisode::getEpisode)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName(
            "jutsu_sync_state singleton: initIfAbsent is no-op on second call; update writes"
                    + " through; CHECK constraint blocks non-singleton inserts")
    void syncStateSingletonContract() {
        LocalDateTime now = LocalDateTime.now().withNano(0);

        syncStateRepository.initIfAbsent(JutsuSyncState.empty(now));
        syncStateRepository.initIfAbsent(JutsuSyncState.empty(now.plusSeconds(1))); // no-op

        JutsuSyncState fresh = syncStateRepository.findSingleton().orElseThrow();
        assertThat(fresh.getId()).isEqualTo(JutsuSyncState.SINGLETON_ID);
        assertThat(fresh.getTotalTitlesSynced()).isZero();

        // Update round-trip
        JutsuSyncState updated = fresh;
        updated.setFullCrawlStartedAt(now);
        updated.setNoticeCursor(18729);
        updated.setNoticeCursorUpdatedAt(now);
        updated.setTotalTitlesSynced(42);
        updated.setUpdatedAt(now.plusMinutes(1));
        syncStateRepository.update(updated);

        JutsuSyncState reloaded = syncStateRepository.findSingleton().orElseThrow();
        assertThat(reloaded.getNoticeCursor()).isEqualTo(18729);
        assertThat(reloaded.getTotalTitlesSynced()).isEqualTo(42);
        assertThat(reloaded.getFullCrawlStartedAt()).isEqualTo(now);
    }
}
