package com.orinuno.catalog.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.catalog.api.CatalogPublicApi;
import com.orinuno.catalog.model.CatalogContent;
import com.orinuno.catalog.model.CatalogContentExternalId;
import com.orinuno.catalog.model.CatalogContentKind;
import com.orinuno.catalog.model.CatalogSourceType;
import com.orinuno.catalog.repository.CatalogContentExternalIdRepository;
import com.orinuno.catalog.repository.CatalogContentRepository;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import com.orinuno.jutsu.sync.JutsuCatalogIngestion;
import com.orinuno.model.KodikContent;
import com.orinuno.repository.ContentRepository;
import com.orinuno.service.KodikCatalogIngestion;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.groups.Tuple;
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
 * Full end-to-end integration test for the L3 ingestion pipeline (ARCH-0016 P1b Step 1.C). Verifies
 * the contract that the unit tests can't: that a real MySQL instance with all migrations applied
 * actually produces the cross-source merges this whole step exists for.
 *
 * <p>Boots a MySQL 8 container, runs Liquibase against it (so {@code kodik_content}, {@code
 * jutsu_title}, and the four {@code catalog_*} tables exist for real), wires the production {@link
 * CatalogPublicApi} alongside both ingestion bridges, and walks the lifecycle:
 *
 * <ol>
 *   <li><strong>jut.su sync (wave 1)</strong> — insert a {@link JutsuTitle}, call {@link
 *       JutsuCatalogIngestion#ingest(JutsuTitle)}; assert one canonical row with one binding;
 *   <li><strong>jut.su sync (wave 2, idempotent re-fetch)</strong> — re-upsert the same slug, call
 *       ingest again; assert still one canonical row, still one binding (same id);
 *   <li><strong>Kodik sync with overlapping shikimori_id</strong> — first Kodik row carrying {@code
 *       shikimori_id="1"} creates a fresh canonical row anchored on shikimori; second Kodik row
 *       with a <em>different</em> {@code kodikId} but the <em>same</em> shikimori_id resolves to
 *       that same canonical row via the SHIKIMORI lookup index, attaching a second KODIK binding
 *       without duplicating the canonical row. This is the core "merge across sources" invariant
 *       the bridge exists to guarantee;
 *   <li><strong>chrome-protection across partial Kodik refresh</strong> — first Kodik ingest
 *       provides full chrome (titleRu / year / kind=ANIME); a later partial refresh (only ids,
 *       kind=UNKNOWN) must NOT blank the canonical chrome (COALESCE-protected) and must NOT demote
 *       kind from ANIME back to UNKNOWN.
 * </ol>
 *
 * <p>Tagged {@code "e2e"} — excluded from default {@code mvn test} via {@code excludedGroups} in
 * surefire. Run with {@code mvn -pl orinuno-app test -Pe2e -Dtest=CatalogIngestionIT}.
 */
@Tag("e2e")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
        properties = {
            "orinuno.kodik.validate-on-startup=false",
            "orinuno.kodik.auto-discovery-enabled=false",
            "orinuno.kodik.bootstrap-from-env=false",
            "orinuno.kodik.token=catalog-ingest-it-fake-token",
            "orinuno.kodik.catalog-ingestion.enabled=true",
            "orinuno.providers.jutsu.sync.catalog-ingestion.enabled=true",
            "orinuno.playwright.enabled=false",
            "orinuno.security.api-key=",
            "orinuno.cache.reference.enabled=false",
            "spring.liquibase.contexts=default"
        })
class CatalogIngestionIT {

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

    @Autowired private JutsuCatalogIngestion jutsuIngestion;
    @Autowired private KodikCatalogIngestion kodikIngestion;
    @Autowired private JutsuTitleRepository jutsuTitleRepository;
    @Autowired private ContentRepository kodikContentRepository;
    @Autowired private CatalogContentRepository catalogContentRepository;
    @Autowired private CatalogContentExternalIdRepository externalIdRepository;

    @Test
    @DisplayName(
            "jut.su sync wave-1 + wave-2 idempotent: one slug → one canonical row → one (JUTSU,"
                    + " slug) binding regardless of how many times the worker re-ingests")
    void jutsuIngestionIsIdempotent() {
        LocalDateTime t0 = LocalDateTime.now().withNano(0);

        // Wave 1: full crawl observes "naruto-jutsu-it" for the first time.
        JutsuTitle wave1 =
                JutsuTitle.builder()
                        .slug("naruto-jutsu-it")
                        .siteId(101)
                        .title("Наруто")
                        .originalTitle("Naruto")
                        .yearBucket("2002")
                        .genresCsv("action,adventure")
                        .typesCsv("shonen")
                        .catalogEpisodeCount(220)
                        .catalogPosition(1)
                        .catalogFetchedAt(t0)
                        .firstSeenAt(t0)
                        .lastSeenAt(t0)
                        .build();
        jutsuTitleRepository.upsert(wave1);
        jutsuIngestion.ingest(wave1);

        long canonicalIdAfterWave1 =
                externalIdRepository
                        .findByExternalId(CatalogSourceType.JUTSU, "naruto-jutsu-it")
                        .orElseThrow()
                        .getContentId();
        CatalogContent content =
                catalogContentRepository.findById(canonicalIdAfterWave1).orElseThrow();
        assertThat(content.getKind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(content.getTitleRu()).isEqualTo("Наруто");
        assertThat(content.getTitleEn()).isEqualTo("Naruto");
        assertThat(content.getYear()).isEqualTo(2002);

        // Wave 2: notice walker re-observes the same slug (placeholder path in real life — see
        // JutsuCatalogSyncService.noticeToPlaceholderTitle). Ingestion must be a no-op at the
        // canonical-row level — we still want exactly one binding pointing at exactly one
        // canonical row.
        JutsuTitle wave2 =
                JutsuTitle.builder()
                        .slug("naruto-jutsu-it")
                        .title("Наруто")
                        .firstSeenAt(t0.plusHours(1))
                        .lastSeenAt(t0.plusHours(1))
                        .build();
        jutsuTitleRepository.upsert(wave2);
        jutsuIngestion.ingest(wave2);

        long canonicalIdAfterWave2 =
                externalIdRepository
                        .findByExternalId(CatalogSourceType.JUTSU, "naruto-jutsu-it")
                        .orElseThrow()
                        .getContentId();
        assertThat(canonicalIdAfterWave2)
                .as("re-ingestion must resolve to the same canonical row, not create a new one")
                .isEqualTo(canonicalIdAfterWave1);

        List<CatalogContentExternalId> bindings =
                externalIdRepository.findByContentId(canonicalIdAfterWave2);
        assertThat(bindings)
                .as("idempotent ingestion must not duplicate the (JUTSU, slug) binding")
                .hasSize(1);
        assertThat(bindings.get(0).getSourceType()).isEqualTo(CatalogSourceType.JUTSU);
        assertThat(bindings.get(0).getExternalId()).isEqualTo("naruto-jutsu-it");
    }

    @Test
    @DisplayName(
            "Kodik cross-source merge: two Kodik rows sharing shikimori_id collapse into one"
                    + " canonical row via the SHIKIMORI identity-column lookup; both KODIK"
                    + " bindings + the SHIKIMORI binding all attach to the same id")
    void kodikRowsWithSameShikimoriIdMergeIntoOneCanonicalRow() {
        long canonicalCountBefore = catalogContentRepository.count();

        // First Kodik row: anime "Berserk" with shikimori_id="33" (1997 series).
        KodikContent first =
                KodikContent.builder()
                        .kodikId("anime-serial-berserk-1997")
                        .type("anime-serial")
                        .title("Берсерк")
                        .titleOrig("Berserk")
                        .year(1997)
                        .shikimoriId("33")
                        .kinopoiskId("260353")
                        .build();
        kodikContentRepository.insert(first);
        kodikIngestion.ingest(first);

        long canonicalCountAfterFirst = catalogContentRepository.count();
        assertThat(canonicalCountAfterFirst - canonicalCountBefore)
                .as("first Kodik ingest creates exactly one new canonical row")
                .isEqualTo(1L);

        CatalogContent afterFirst = catalogContentRepository.findByShikimoriId("33").orElseThrow();
        assertThat(afterFirst.getKind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(afterFirst.getTitleRu()).isEqualTo("Берсерк");
        assertThat(afterFirst.getKinopoiskId()).isEqualTo("260353");

        // Second Kodik row: a different translation slot (different kodikId), same shikimori_id.
        // The resolver MUST find the existing canonical row via shikimori lookup and attach the
        // new KODIK binding to it, not insert a duplicate canonical row.
        KodikContent second =
                KodikContent.builder()
                        .kodikId("anime-serial-berserk-1997-russian-dub")
                        .type("anime-serial")
                        .title("Берсерк")
                        .titleOrig("Berserk")
                        .year(1997)
                        .shikimoriId("33")
                        .build();
        kodikContentRepository.insert(second);
        kodikIngestion.ingest(second);

        long canonicalCountAfterSecond = catalogContentRepository.count();
        assertThat(canonicalCountAfterSecond - canonicalCountAfterFirst)
                .as(
                        "second Kodik ingest with overlapping shikimori_id must MERGE, not create"
                                + " a new canonical row")
                .isZero();

        CatalogContent afterSecond = catalogContentRepository.findByShikimoriId("33").orElseThrow();
        assertThat(afterSecond.getId())
                .as("both ingests resolve to the same canonical id")
                .isEqualTo(afterFirst.getId());

        List<CatalogContentExternalId> bindings =
                externalIdRepository.findByContentId(afterSecond.getId());
        assertThat(bindings)
                .as(
                        "exactly four bindings on the merged row: two KODIK (one per kodikId),"
                                + " one SHIKIMORI, one KINOPOISK")
                .extracting(
                        CatalogContentExternalId::getSourceType,
                        CatalogContentExternalId::getExternalId)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(CatalogSourceType.KODIK, "anime-serial-berserk-1997"),
                        Tuple.tuple(
                                CatalogSourceType.KODIK, "anime-serial-berserk-1997-russian-dub"),
                        Tuple.tuple(CatalogSourceType.SHIKIMORI, "33"),
                        Tuple.tuple(CatalogSourceType.KINOPOISK, "260353"));
    }

    @Test
    @DisplayName(
            "Kodik partial refresh preserves chrome: first ingest sets titleRu/year/kind=ANIME,"
                    + " later partial ingest with kind=UNKNOWN must NOT overwrite either")
    void kodikPartialRefreshDoesNotBlankChrome() {
        KodikContent fullChrome =
                KodikContent.builder()
                        .kodikId("anime-serial-aot-2013")
                        .type("anime-serial")
                        .title("Атака титанов")
                        .titleOrig("Attack on Titan")
                        .year(2013)
                        .shikimoriId("16498")
                        .build();
        kodikContentRepository.insert(fullChrome);
        kodikIngestion.ingest(fullChrome);

        long canonicalId =
                catalogContentRepository.findByShikimoriId("16498").orElseThrow().getId();

        // Simulate a partial refresh: a row that lost its `type` upstream and now has only the ids
        // and a generic title. mapKind("") returns UNKNOWN — this must NOT downgrade the canonical
        // row from ANIME.
        KodikContent partial =
                KodikContent.builder()
                        .kodikId("anime-serial-aot-2013")
                        .type("") // will translate to UNKNOWN
                        .shikimoriId("16498")
                        .build();
        kodikIngestion.ingest(partial);

        CatalogContent merged = catalogContentRepository.findById(canonicalId).orElseThrow();
        assertThat(merged.getKind())
                .as("UNKNOWN from a partial refresh must not demote a previously-set ANIME kind")
                .isEqualTo(CatalogContentKind.ANIME);
        assertThat(merged.getTitleRu())
                .as("partial refresh without titleRu must not blank the original")
                .isEqualTo("Атака титанов");
        assertThat(merged.getTitleEn()).isEqualTo("Attack on Titan");
        assertThat(merged.getYear()).isEqualTo(2013);
    }

    @Test
    @DisplayName(
            "jut.su + Kodik for unrelated titles: bridge correctly leaves them as TWO independent"
                    + " canonical rows when no external-db id overlaps")
    void jutsuAndKodikWithNoOverlapStayIndependent() {
        // jut.su side: an anime jut.su has but Kodik doesn't.
        JutsuTitle jutsuOnly =
                JutsuTitle.builder()
                        .slug("indie-anime-only-on-jutsu")
                        .title("Только на jut.su")
                        .yearBucket("2024")
                        .firstSeenAt(LocalDateTime.now().withNano(0))
                        .lastSeenAt(LocalDateTime.now().withNano(0))
                        .build();
        jutsuTitleRepository.upsert(jutsuOnly);
        jutsuIngestion.ingest(jutsuOnly);

        // Kodik side: an unrelated film.
        KodikContent kodikOnly =
                KodikContent.builder()
                        .kodikId("russian-movie-island-2006")
                        .type("russian-movie")
                        .title("Остров")
                        .year(2006)
                        .kinopoiskId("253245")
                        .build();
        kodikContentRepository.insert(kodikOnly);
        kodikIngestion.ingest(kodikOnly);

        long jutsuCanonicalId =
                externalIdRepository
                        .findByExternalId(CatalogSourceType.JUTSU, "indie-anime-only-on-jutsu")
                        .orElseThrow()
                        .getContentId();
        long kodikCanonicalId =
                externalIdRepository
                        .findByExternalId(CatalogSourceType.KODIK, "russian-movie-island-2006")
                        .orElseThrow()
                        .getContentId();

        assertThat(jutsuCanonicalId)
                .as(
                        "no shared external-db id ⇒ no merge: jut.su row and Kodik row must live"
                                + " in two distinct canonical rows")
                .isNotEqualTo(kodikCanonicalId);

        CatalogContent jutsuRow = catalogContentRepository.findById(jutsuCanonicalId).orElseThrow();
        CatalogContent kodikRow = catalogContentRepository.findById(kodikCanonicalId).orElseThrow();
        assertThat(jutsuRow.getKind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(kodikRow.getKind()).isEqualTo(CatalogContentKind.MOVIE);
        assertThat(kodikRow.getKinopoiskId()).isEqualTo("253245");
    }
}
