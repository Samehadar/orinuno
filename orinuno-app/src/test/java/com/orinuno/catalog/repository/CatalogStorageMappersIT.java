package com.orinuno.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.catalog.model.CatalogContent;
import com.orinuno.catalog.model.CatalogContentExternalId;
import com.orinuno.catalog.model.CatalogContentKind;
import com.orinuno.catalog.model.CatalogEpisode;
import com.orinuno.catalog.model.CatalogEpisodeSourceLink;
import com.orinuno.catalog.model.CatalogSourceType;
import java.time.LocalDate;
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
 * Phase2-style Testcontainers integration test for the L3 canonical-catalog mappers (ARCH-0016 P1b
 * Step 1.A). Boots a MySQL 8 container, runs Liquibase against it, and exercises the four
 * repositories at the SQL boundary.
 *
 * <p>What it asserts (the contract Step 1.B and 1.C will rely on):
 *
 * <ul>
 *   <li>{@code catalog_content} insert returns the auto-generated id; identity-column lookups go
 *       through the dedicated {@code findByXxxId} indexes;
 *   <li>{@code catalog_content.update} {@code COALESCE}-protects identity and chrome columns —
 *       partial updates from one source must not blank fields another source already filled. The
 *       only way to nullify an identity column is the explicit {@code clearIdentityColumn(...)};
 *   <li>{@code catalog_content_external_id} unique on {@code (sourceType, externalId)}; {@code
 *       reassignContent} re-points an existing binding atomically;
 *   <li>{@code catalog_episode} upsert is composite-key idempotent on {@code (contentId, season,
 *       episode)} and protects {@code title} / {@code airDate} via {@code COALESCE};
 *   <li>{@code catalog_episode_source_link} upsert is unique on {@code (catalogEpisodeId,
 *       episodeSourceId)} and is a no-op on duplicate.
 * </ul>
 *
 * <p>Tagged {@code "e2e"} — excluded from default {@code mvn test} via {@code excludedGroups} in
 * surefire. Run with {@code mvn -pl orinuno-app test -Pe2e -Dtest=CatalogStorageMappersIT}.
 */
@Tag("e2e")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            "orinuno.kodik.validate-on-startup=false",
            "orinuno.kodik.auto-discovery-enabled=false",
            "orinuno.kodik.bootstrap-from-env=false",
            "orinuno.kodik.token=catalog-l3-fake-token",
            "orinuno.playwright.enabled=false",
            "orinuno.security.api-key=",
            "orinuno.cache.reference.enabled=false",
            "spring.liquibase.contexts=default"
        })
class CatalogStorageMappersIT {

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

    @Autowired private CatalogContentRepository contentRepository;
    @Autowired private CatalogContentExternalIdRepository externalIdRepository;
    @Autowired private CatalogEpisodeRepository episodeRepository;
    @Autowired private CatalogEpisodeSourceLinkRepository linkRepository;

    @Test
    @DisplayName(
            "catalog_content insert returns generated id; identity-column lookups go through"
                    + " dedicated indexes; update COALESCE-protects identity columns")
    void catalogContentInsertAndUpdate() {
        LocalDateTime now = LocalDateTime.now().withNano(0);

        CatalogContent fresh =
                CatalogContent.builder()
                        .titleRu("Атака титанов")
                        .titleEn("Attack on Titan")
                        .kind(CatalogContentKind.ANIME)
                        .year(2013)
                        .shikimoriId("16498")
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        contentRepository.insert(fresh);

        assertThat(fresh.getId())
                .as("auto-generated id is set on the argument by useGeneratedKeys")
                .isNotNull();
        assertThat(fresh.getId()).isPositive();

        Optional<CatalogContent> reloaded = contentRepository.findById(fresh.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getKind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(reloaded.get().getShikimoriId()).isEqualTo("16498");

        // Identity-column lookup hits the dedicated index.
        Optional<CatalogContent> byShikimori = contentRepository.findByShikimoriId("16498");
        assertThat(byShikimori).isPresent();
        assertThat(byShikimori.get().getId()).isEqualTo(fresh.getId());

        // COALESCE protection on update: partial update with only mal_id must keep titleRu /
        // shikimori_id intact.
        CatalogContent partial =
                CatalogContent.builder()
                        .id(fresh.getId())
                        .malId("16498")
                        .updatedAt(now.plusMinutes(1))
                        .build();
        contentRepository.update(partial);

        CatalogContent merged = contentRepository.findById(fresh.getId()).orElseThrow();
        assertThat(merged.getTitleRu()).isEqualTo("Атака титанов");
        assertThat(merged.getShikimoriId()).isEqualTo("16498");
        assertThat(merged.getMalId()).isEqualTo("16498");
        assertThat(merged.getUpdatedAt()).isEqualTo(now.plusMinutes(1));
    }

    @Test
    @DisplayName(
            "catalog_content.clearIdentityColumn nullifies one identity column without touching"
                    + " the rest; whitelisted column names only")
    void clearIdentityColumnIsSurgical() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        CatalogContent c =
                CatalogContent.builder()
                        .titleEn("X")
                        .kind(CatalogContentKind.ANIME)
                        .shikimoriId("1")
                        .malId("2")
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        contentRepository.insert(c);

        contentRepository.clearIdentityColumn(c.getId(), "shikimori_id");

        CatalogContent after = contentRepository.findById(c.getId()).orElseThrow();
        assertThat(after.getShikimoriId()).isNull();
        assertThat(after.getMalId()).isEqualTo("2");

        // Unknown column is a no-op (whitelist's <otherwise/> branch sets id = id).
        contentRepository.clearIdentityColumn(c.getId(), "totally; drop table users");
        assertThat(contentRepository.findById(c.getId()).orElseThrow().getMalId()).isEqualTo("2");
    }

    @Test
    @DisplayName(
            "catalog_content_external_id: unique on (sourceType, externalId); reassignContent"
                    + " re-points binding atomically")
    void externalIdAttachAndReassign() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        CatalogContent a = insertContent("A", now);
        CatalogContent b = insertContent("B", now);

        CatalogContentExternalId binding =
                CatalogContentExternalId.builder()
                        .contentId(a.getId())
                        .sourceType(CatalogSourceType.SHIKIMORI)
                        .externalId("777")
                        .createdAt(now)
                        .build();
        externalIdRepository.insert(binding);
        assertThat(binding.getId()).isNotNull();

        Optional<CatalogContentExternalId> roundTrip =
                externalIdRepository.findByExternalId(CatalogSourceType.SHIKIMORI, "777");
        assertThat(roundTrip).isPresent();
        assertThat(roundTrip.get().getContentId()).isEqualTo(a.getId());

        int reassigned =
                externalIdRepository.reassignContent(CatalogSourceType.SHIKIMORI, "777", b.getId());
        assertThat(reassigned).isEqualTo(1);

        CatalogContentExternalId moved =
                externalIdRepository
                        .findByExternalId(CatalogSourceType.SHIKIMORI, "777")
                        .orElseThrow();
        assertThat(moved.getContentId()).isEqualTo(b.getId());

        // Multiple bindings of the same source-type can coexist on one content.
        externalIdRepository.insert(
                CatalogContentExternalId.builder()
                        .contentId(b.getId())
                        .sourceType(CatalogSourceType.KODIK)
                        .externalId("kodik-raw-abc")
                        .createdAt(now)
                        .build());
        externalIdRepository.insert(
                CatalogContentExternalId.builder()
                        .contentId(b.getId())
                        .sourceType(CatalogSourceType.KODIK)
                        .externalId("kodik-raw-def")
                        .createdAt(now)
                        .build());

        List<CatalogContentExternalId> bBindings =
                externalIdRepository.findByContentIdAndSource(b.getId(), CatalogSourceType.KODIK);
        assertThat(bBindings).hasSize(2);
        assertThat(bBindings)
                .extracting(CatalogContentExternalId::getExternalId)
                .containsExactlyInAnyOrder("kodik-raw-abc", "kodik-raw-def");
    }

    @Test
    @DisplayName(
            "catalog_episode upsert: composite-key idempotent on (contentId, season, episode);"
                    + " title + airDate COALESCE-protected on partial refresh")
    void episodeUpsertIsIdempotentAndProtectsColumns() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        CatalogContent show = insertContent("Show", now);

        episodeRepository.upsert(
                CatalogEpisode.builder()
                        .contentId(show.getId())
                        .season(1)
                        .episode(1)
                        .title("Pilot")
                        .airDate(LocalDate.of(2020, 1, 5))
                        .createdAt(now)
                        .updatedAt(now)
                        .build());

        CatalogEpisode wave1 =
                episodeRepository.findByContentSeasonEpisode(show.getId(), 1, 1).orElseThrow();
        assertThat(wave1.getTitle()).isEqualTo("Pilot");

        // Partial refresh from another source: only updated_at; title and airDate must stay.
        episodeRepository.upsert(
                CatalogEpisode.builder()
                        .contentId(show.getId())
                        .season(1)
                        .episode(1)
                        .createdAt(now.plusDays(1))
                        .updatedAt(now.plusDays(1))
                        .build());

        CatalogEpisode wave2 =
                episodeRepository.findByContentSeasonEpisode(show.getId(), 1, 1).orElseThrow();
        assertThat(wave2.getTitle()).isEqualTo("Pilot");
        assertThat(wave2.getAirDate()).isEqualTo(LocalDate.of(2020, 1, 5));
        assertThat(wave2.getUpdatedAt()).isEqualTo(now.plusDays(1));
    }

    @Test
    @DisplayName(
            "catalog_episode_source_link upsert is unique on (catalogEpisodeId, episodeSourceId)"
                    + " and a no-op on duplicate insert")
    void episodeSourceLinkUpsertIdempotent() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        CatalogContent show = insertContent("LinkShow", now);
        episodeRepository.upsert(
                CatalogEpisode.builder()
                        .contentId(show.getId())
                        .season(1)
                        .episode(1)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        long catalogEpisodeId =
                episodeRepository
                        .findByContentSeasonEpisode(show.getId(), 1, 1)
                        .orElseThrow()
                        .getId();

        // Pretend episode_source row #500 exists in the core context (we don't have an FK to
        // enforce, that's the point of the soft reference per ADR 0016).
        long fakeEpisodeSourceId = 500L;
        CatalogEpisodeSourceLink link =
                CatalogEpisodeSourceLink.builder()
                        .catalogEpisodeId(catalogEpisodeId)
                        .episodeSourceId(fakeEpisodeSourceId)
                        .createdAt(now)
                        .build();
        linkRepository.upsert(link);

        // Repeat insert is idempotent: count stays at 1.
        linkRepository.upsert(
                CatalogEpisodeSourceLink.builder()
                        .catalogEpisodeId(catalogEpisodeId)
                        .episodeSourceId(fakeEpisodeSourceId)
                        .createdAt(now.plusMinutes(1))
                        .build());

        assertThat(linkRepository.findByCatalogEpisode(catalogEpisodeId)).hasSize(1);
    }

    private CatalogContent insertContent(String title, LocalDateTime now) {
        CatalogContent c =
                CatalogContent.builder()
                        .titleEn(title)
                        .kind(CatalogContentKind.ANIME)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        contentRepository.insert(c);
        return c;
    }
}
