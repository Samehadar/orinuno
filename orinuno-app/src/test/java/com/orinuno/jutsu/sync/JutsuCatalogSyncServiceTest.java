package com.orinuno.jutsu.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.model.JutsuSyncState;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuSyncStateRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/**
 * Unit-level coverage for the catalog full-crawl loop. Mocks the SDK + repos so we can rehearse the
 * fresh-start / resume / page-cap / terminus / failure branches without booting MySQL.
 */
@ExtendWith(MockitoExtension.class)
class JutsuCatalogSyncServiceTest {

    @Mock private JutsuClient client;
    @Mock private JutsuTitleRepository titleRepository;
    @Mock private JutsuEpisodeRepository episodeRepository;
    @Mock private JutsuSyncStateRepository syncStateRepository;
    @Mock private JutsuCatalogIngestion catalogIngestion;

    private OrinunoProperties properties;
    private JutsuCatalogSyncService service;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getProviders().getJutsu().getSync().setEnabled(true);
        properties.getProviders().getJutsu().getSync().getFullCrawl().setEnabled(true);
        properties.getProviders().getJutsu().getSync().getFullCrawl().setMaxPagesPerTick(50);
        service =
                new JutsuCatalogSyncService(
                        client,
                        titleRepository,
                        episodeRepository,
                        syncStateRepository,
                        properties,
                        catalogIngestion);
    }

    @Test
    @DisplayName("disabled sync makes runFullCrawlOnce a true no-op (no SDK / repo touches)")
    void disabledSyncShortCircuits() {
        properties.getProviders().getJutsu().getSync().setEnabled(false);

        JutsuCatalogSyncService.FullCrawlResult result = service.runFullCrawlOnce(10);

        assertThat(result.pagesFetched()).isZero();
        assertThat(result.titlesUpserted()).isZero();
        assertThat(result.completed()).isFalse();
        verify(client, never()).browseCatalog(any(JutsuCatalogRequest.class));
        verify(titleRepository, never()).upsert(any());
        verify(syncStateRepository, never()).update(any());
    }

    @Test
    @DisplayName(
            "fresh tick walks from page 1 to terminus and marks completion in a single state"
                    + " update")
    void freshTickWalksToTerminus() {
        when(syncStateRepository.findSingleton())
                .thenReturn(Optional.of(JutsuSyncState.empty(LocalDateTime.now())));
        when(client.browseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.just(pageOf(1, true, entry("naruto", "Наруто"))))
                .thenReturn(Mono.just(pageOf(2, false, entry("bleach", "Блич"))));

        JutsuCatalogSyncService.FullCrawlResult result = service.runFullCrawlOnce(10);

        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.titlesUpserted()).isEqualTo(2);
        assertThat(result.completed()).isTrue();
        assertThat(result.lastPage()).isEqualTo(2);
        assertThat(result.resumedPreviousCrawl()).isFalse();
        assertThat(result.error()).isNull();
        verify(titleRepository, times(2)).upsert(any(JutsuTitle.class));

        ArgumentCaptor<JutsuSyncState> stateCaptor = ArgumentCaptor.forClass(JutsuSyncState.class);
        verify(syncStateRepository).update(stateCaptor.capture());
        JutsuSyncState saved = stateCaptor.getValue();
        assertThat(saved.getFullCrawlLastPage()).isEqualTo(2);
        assertThat(saved.getFullCrawlCompletedAt()).isNotNull();
        assertThat(saved.getFullCrawlTotalPages()).isEqualTo(2);
        assertThat(saved.getTotalTitlesSynced()).isEqualTo(2);
        assertThat(saved.getLastError()).isNull();
    }

    @Test
    @DisplayName(
            "page cap stops the tick early without setting completedAt — the next tick resumes"
                    + " from lastPage + 1")
    void pageCapStopsTickWithoutCompleting() {
        properties.getProviders().getJutsu().getSync().getFullCrawl().setMaxPagesPerTick(2);
        when(syncStateRepository.findSingleton())
                .thenReturn(Optional.of(JutsuSyncState.empty(LocalDateTime.now())));
        when(client.browseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.just(pageOf(1, true, entry("a", "A"))))
                .thenReturn(Mono.just(pageOf(2, true, entry("b", "B"))));

        JutsuCatalogSyncService.FullCrawlResult result = service.runFullCrawlOnce(2);

        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.completed()).isFalse();
        assertThat(result.lastPage()).isEqualTo(2);

        ArgumentCaptor<JutsuSyncState> stateCaptor = ArgumentCaptor.forClass(JutsuSyncState.class);
        verify(syncStateRepository).update(stateCaptor.capture());
        JutsuSyncState saved = stateCaptor.getValue();
        assertThat(saved.getFullCrawlLastPage()).isEqualTo(2);
        assertThat(saved.getFullCrawlCompletedAt())
                .as(
                        "completedAt must remain null until we actually hit hasMore=false — "
                                + "otherwise the next tick would wrongly restart from page 1")
                .isNull();
    }

    @Test
    @DisplayName(
            "tick resumes an in-progress crawl from fullCrawlLastPage + 1 instead of restarting"
                    + " at page 1")
    void resumeContinuesFromCheckpoint() {
        LocalDateTime startedAt = LocalDateTime.now().minusHours(1);
        JutsuSyncState inProgress = JutsuSyncState.empty(startedAt);
        inProgress.setFullCrawlStartedAt(startedAt);
        inProgress.setFullCrawlLastPage(7);
        inProgress.setFullCrawlCompletedAt(null);
        when(syncStateRepository.findSingleton()).thenReturn(Optional.of(inProgress));
        when(client.browseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.just(pageOf(8, false, entry("x", "X"))));

        JutsuCatalogSyncService.FullCrawlResult result = service.runFullCrawlOnce(50);

        assertThat(result.resumedPreviousCrawl()).isTrue();
        assertThat(result.lastPage()).isEqualTo(8);
        assertThat(result.completed()).isTrue();

        ArgumentCaptor<JutsuCatalogRequest> reqCaptor =
                ArgumentCaptor.forClass(JutsuCatalogRequest.class);
        verify(client).browseCatalog(reqCaptor.capture());
        assertThat(reqCaptor.getValue().page()).isEqualTo(8);

        ArgumentCaptor<JutsuSyncState> stateCaptor = ArgumentCaptor.forClass(JutsuSyncState.class);
        verify(syncStateRepository).update(stateCaptor.capture());
        JutsuSyncState saved = stateCaptor.getValue();
        assertThat(saved.getFullCrawlStartedAt())
                .as("resume must preserve the original startedAt of the in-progress crawl")
                .isEqualTo(startedAt);
    }

    @Test
    @DisplayName(
            "after the previous crawl completed, the next tick begins a fresh crawl at page 1"
                    + " and resets startedAt")
    void completedCrawlReboots() {
        LocalDateTime previousStart = LocalDateTime.now().minusDays(1);
        JutsuSyncState completed = JutsuSyncState.empty(previousStart);
        completed.setFullCrawlStartedAt(previousStart);
        completed.setFullCrawlCompletedAt(previousStart.plusMinutes(10));
        completed.setFullCrawlLastPage(120);
        completed.setFullCrawlTotalPages(120);
        when(syncStateRepository.findSingleton()).thenReturn(Optional.of(completed));
        when(client.browseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.just(pageOf(1, false, entry("y", "Y"))));

        JutsuCatalogSyncService.FullCrawlResult result = service.runFullCrawlOnce(50);

        assertThat(result.resumedPreviousCrawl()).isFalse();
        assertThat(result.lastPage()).isEqualTo(1);
        ArgumentCaptor<JutsuCatalogRequest> reqCaptor =
                ArgumentCaptor.forClass(JutsuCatalogRequest.class);
        verify(client).browseCatalog(reqCaptor.capture());
        assertThat(reqCaptor.getValue().page()).isEqualTo(1);

        ArgumentCaptor<JutsuSyncState> stateCaptor = ArgumentCaptor.forClass(JutsuSyncState.class);
        verify(syncStateRepository).update(stateCaptor.capture());
        JutsuSyncState saved = stateCaptor.getValue();
        assertThat(saved.getFullCrawlStartedAt())
                .as("fresh crawl must bump startedAt past the previous completedAt")
                .isAfter(previousStart);
    }

    @Test
    @DisplayName("fetch error stops the tick early and writes lastError into state")
    void fetchFailureRecordsLastErrorButDoesNotPropagate() {
        when(syncStateRepository.findSingleton())
                .thenReturn(Optional.of(JutsuSyncState.empty(LocalDateTime.now())));
        when(client.browseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.just(pageOf(1, true, entry("a", "A"))))
                .thenReturn(Mono.error(new IllegalStateException("simulated upstream blip")));

        JutsuCatalogSyncService.FullCrawlResult result = service.runFullCrawlOnce(10);

        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.titlesUpserted()).isEqualTo(1);
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).contains("simulated upstream blip");

        ArgumentCaptor<JutsuSyncState> stateCaptor = ArgumentCaptor.forClass(JutsuSyncState.class);
        verify(syncStateRepository).update(stateCaptor.capture());
        JutsuSyncState saved = stateCaptor.getValue();
        assertThat(saved.getLastError()).contains("simulated upstream blip");
        assertThat(saved.getLastErrorAt()).isNotNull();
        assertThat(saved.getFullCrawlLastPage())
                .as("cursor must advance to the LAST PERSISTED page, not the failing one")
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "toTitle joins genre/type/year slugs into deterministic CSVs so re-fetches don't"
                    + " produce diff churn")
    void toTitleProducesDeterministicCsvs() {
        LocalDateTime now = LocalDateTime.now();
        JutsuCatalogEntry entry =
                new JutsuCatalogEntry(
                        29,
                        "naruto",
                        "Наруто",
                        "Naruto",
                        "https://jut.su/naruto.jpg",
                        220,
                        3,
                        Set.of(JutsuGenre.ACTION, JutsuGenre.ADVENTURE, JutsuGenre.COMEDY),
                        Set.of(JutsuType.SHONEN),
                        Optional.of(JutsuYear.Y_2000_2007));

        JutsuTitle row = JutsuCatalogSyncService.toTitle(entry, /* page= */ 3, /* slot= */ 7, now);

        assertThat(row.getSlug()).isEqualTo("naruto");
        assertThat(row.getSiteId()).isEqualTo(29);
        assertThat(row.getOriginalTitle()).isEqualTo("Naruto");
        assertThat(row.getCatalogEpisodeCount()).isEqualTo(220);
        assertThat(row.getCatalogMovieCount()).isEqualTo(3);
        assertThat(row.getYearBucket()).isEqualTo("2000-2007");
        assertThat(row.getGenresCsv())
                .as("genre CSV must be sorted (alphabetical) for deterministic upsert payloads")
                .isEqualTo("action,adventure,comedy");
        assertThat(row.getTypesCsv()).isEqualTo("shonen");
        assertThat(row.getCatalogPosition())
                .as("catalogPosition = (page-1)*30 + slot — drives the read side's by-rating sort")
                .isEqualTo((3 - 1) * 30 + 7);
        assertThat(row.getCatalogFetchedAt()).isEqualTo(now);
        assertThat(row.getFirstSeenAt()).isEqualTo(now);
        assertThat(row.getLastSeenAt()).isEqualTo(now);
        assertThat(row.getInfoFetchedAt())
                .as("catalog tick must NEVER write info-only fields — that's the info worker's job")
                .isNull();
        assertThat(row.getSynopsis()).isNull();
    }

    @Test
    @DisplayName("missing siteId (-1) is normalised to NULL so the L1 row stays a faithful mirror")
    void missingSiteIdNormalisesToNull() {
        JutsuTitle row =
                JutsuCatalogSyncService.toTitle(
                        new JutsuCatalogEntry(
                                -1,
                                "x",
                                "X",
                                null,
                                null,
                                null,
                                null,
                                Set.of(),
                                Set.of(),
                                Optional.empty()),
                        1,
                        1,
                        LocalDateTime.now());

        assertThat(row.getSiteId()).isNull();
        assertThat(row.getGenresCsv()).isNull();
        assertThat(row.getTypesCsv()).isNull();
        assertThat(row.getYearBucket()).isNull();
        assertThat(row.getCatalogPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("full-crawl tick stamps catalog_position monotonically across pages and slots")
    void fullCrawlAssignsMonotonicCatalogPositions() {
        when(syncStateRepository.findSingleton())
                .thenReturn(Optional.of(JutsuSyncState.empty(LocalDateTime.now())));
        // Page 1 has 3 entries (slots 1..3 → positions 1..3). Page 2 has 2 entries (slots 1..2 →
        // positions 31, 32). hasMore=false on page 2 closes the crawl.
        JutsuCatalogEntry e1 = entry("a", "A");
        JutsuCatalogEntry e2 = entry("b", "B");
        JutsuCatalogEntry e3 = entry("c", "C");
        JutsuCatalogEntry e4 = entry("d", "D");
        JutsuCatalogEntry e5 = entry("e", "E");
        when(client.browseCatalog(any(JutsuCatalogRequest.class)))
                .thenReturn(Mono.just(new JutsuCatalogPage(List.of(e1, e2, e3), 1, true)))
                .thenReturn(Mono.just(new JutsuCatalogPage(List.of(e4, e5), 2, false)));

        service.runFullCrawlOnce(10);

        ArgumentCaptor<JutsuTitle> upserts = ArgumentCaptor.forClass(JutsuTitle.class);
        verify(titleRepository, times(5)).upsert(upserts.capture());
        List<JutsuTitle> rows = upserts.getAllValues();
        assertThat(rows.get(0).getSlug()).isEqualTo("a");
        assertThat(rows.get(0).getCatalogPosition()).isEqualTo(1);
        assertThat(rows.get(1).getCatalogPosition()).isEqualTo(2);
        assertThat(rows.get(2).getCatalogPosition()).isEqualTo(3);
        assertThat(rows.get(3).getSlug()).isEqualTo("d");
        assertThat(rows.get(3).getCatalogPosition()).isEqualTo(31);
        assertThat(rows.get(4).getCatalogPosition()).isEqualTo(32);
    }

    private static JutsuCatalogPage pageOf(int page, boolean hasMore, JutsuCatalogEntry... rows) {
        return new JutsuCatalogPage(List.of(rows), page, hasMore);
    }

    private static JutsuCatalogEntry entry(String slug, String title) {
        return new JutsuCatalogEntry(
                -1, slug, title, null, null, null, null, Set.of(), Set.of(), Optional.empty());
    }
}
