package com.orinuno.jutsu.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.JutsuSyncProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.drift.JutsuDriftEvent;
import com.orinuno.jutsu.drift.JutsuDriftException;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/** Unit tests for {@link JutsuCatalogSyncService}. NO Spring context — pure Mockito. */
@ExtendWith(MockitoExtension.class)
class JutsuCatalogSyncServiceTest {

    @Mock private JutsuClient jutsuClient;
    @Mock private JutsuTitleRepository titleRepository;
    @Mock private JutsuEpisodeRepository episodeRepository;
    @Mock private JutsuNoticeLockService lockService;
    @Mock private JutsuStalenessTracker stalenessTracker;

    private JutsuCatalogSyncService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock fixedClock =
            Clock.fixed(
                    ZonedDateTime.of(2026, 5, 7, 0, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service =
                new JutsuCatalogSyncService(
                        jutsuClient,
                        titleRepository,
                        episodeRepository,
                        lockService,
                        stalenessTracker,
                        new JutsuSyncProperties(),
                        mapper,
                        fixedClock);
    }

    private static JutsuCatalogEntry sampleEntry(String slug) {
        return new JutsuCatalogEntry(
                Math.abs(slug.hashCode()),
                slug,
                "title-" + slug,
                null,
                null,
                12,
                0,
                Set.of(JutsuGenre.ACTION),
                Set.of(JutsuType.SHONEN),
                Optional.empty());
    }

    @Test
    @DisplayName("Full crawl walks pages forward until hasMore=false; advances cursor")
    void fullCrawlPaginatesUntilEmpty() {
        when(jutsuClient.browseCatalog(1))
                .thenReturn(
                        Mono.just(
                                new JutsuCatalogPage(
                                        List.of(sampleEntry("a"), sampleEntry("b")), 1, true)));
        when(jutsuClient.browseCatalog(2))
                .thenReturn(Mono.just(new JutsuCatalogPage(List.of(sampleEntry("c")), 2, false)));

        service.fullCrawl();

        verify(titleRepository, times(3)).upsert(any());
        verify(lockService).markFullCrawl();
        verify(stalenessTracker).invalidate();
        verify(jutsuClient, never()).browseCatalog(3);
    }

    @Test
    @DisplayName("Catalog upsert path writes to title repo")
    void fullCrawlUpsertsTitle() {
        when(jutsuClient.browseCatalog(1))
                .thenReturn(
                        Mono.just(new JutsuCatalogPage(List.of(sampleEntry("naruto")), 1, false)));

        service.fullCrawl();
        verify(titleRepository).upsert(any());
    }

    @Test
    @DisplayName(
            "Notice incremental: bootstraps cursor when none saved; does NOT process old entries")
    void noticeIncrementalBootstrap() {
        when(lockService.tryAcquire()).thenReturn(true);
        when(lockService.currentCursor()).thenReturn(Optional.empty());
        JutsuNoticeFeed feed = new JutsuNoticeFeed(1500, List.of(sampleNoticeEntry("naruto", 100)));
        when(jutsuClient.getLatestNoticeFeed()).thenReturn(Mono.just(feed));

        service.noticeIncremental();

        verify(lockService).saveCursor(eq(1500));
        verify(titleRepository, never()).upsert(any());
        verify(episodeRepository, never()).upsertBatch(any());
        verify(lockService).release();
    }

    @Test
    @DisplayName(
            "Notice incremental: advances cursor and processes entries newer than saved cursor")
    void noticeIncrementalAdvancesCursor() {
        when(lockService.tryAcquire()).thenReturn(true);
        when(lockService.currentCursor()).thenReturn(Optional.of(1480));
        // newest cursor 1500, saved 1480 → delta 20 → process first 20 entries
        List<JutsuNoticeEntry> entries =
                java.util.stream.IntStream.range(0, 30)
                        .mapToObj(i -> sampleNoticeEntry("anime-" + i, i))
                        .toList();
        JutsuNoticeFeed feed = new JutsuNoticeFeed(1500, entries);
        when(jutsuClient.getLatestNoticeFeed()).thenReturn(Mono.just(feed));

        service.noticeIncremental();

        verify(titleRepository, atLeast(1)).upsert(any());
        verify(episodeRepository).upsertBatch(any());
        verify(lockService).saveCursor(eq(1500));
        verify(lockService).release();
    }

    @Test
    @DisplayName("Notice incremental: cursor up-to-date → nothing to do, but cursor unchanged")
    void noticeIncrementalSkipsWhenUpToDate() {
        when(lockService.tryAcquire()).thenReturn(true);
        when(lockService.currentCursor()).thenReturn(Optional.of(1500));
        JutsuNoticeFeed feed = new JutsuNoticeFeed(1500, List.of(sampleNoticeEntry("naruto", 100)));
        when(jutsuClient.getLatestNoticeFeed()).thenReturn(Mono.just(feed));

        service.noticeIncremental();

        verify(titleRepository, never()).upsert(any());
        verify(episodeRepository, never()).upsertBatch(any());
        verify(lockService, never()).saveCursor(anyInt());
        verify(lockService).release();
    }

    @Test
    @DisplayName("Notice incremental: delta > pageSize logs gap but still advances cursor")
    void noticeIncrementalGapAdvancesCursor() {
        when(lockService.tryAcquire()).thenReturn(true);
        when(lockService.currentCursor()).thenReturn(Optional.of(1000));
        // delta = 500 but feed only has 50 entries
        List<JutsuNoticeEntry> entries =
                java.util.stream.IntStream.range(0, 50)
                        .mapToObj(i -> sampleNoticeEntry("anime-" + i, i))
                        .toList();
        JutsuNoticeFeed feed = new JutsuNoticeFeed(1500, entries);
        when(jutsuClient.getLatestNoticeFeed()).thenReturn(Mono.just(feed));

        service.noticeIncremental();

        verify(episodeRepository).upsertBatch(any());
        verify(lockService).saveCursor(eq(1500));
    }

    @Test
    @DisplayName("Drift in notice walk → cursor nullified to force a fresh bootstrap")
    void driftFallsBackToBootstrap() {
        when(lockService.tryAcquire()).thenReturn(true);
        when(lockService.currentCursor()).thenReturn(Optional.of(1480));
        JutsuDriftEvent ev =
                JutsuDriftEvent.of(JutsuDriftSignal.SCHEMA_VIOLATION, "notice-test", "boom");
        when(jutsuClient.getLatestNoticeFeed()).thenReturn(Mono.error(new JutsuDriftException(ev)));

        service.noticeIncremental();

        verify(lockService).saveCursor(null);
        verify(lockService).release();
    }

    @Test
    @DisplayName("Concurrent notice walk: lock contention skips the second tick")
    void lockPreventsConcurrentNoticeWalk() {
        when(lockService.tryAcquire()).thenReturn(false);

        service.noticeIncremental();

        verify(lockService, never()).currentCursor();
        verify(lockService, never()).saveCursor(anyInt());
        verify(lockService, never()).release();
    }

    private static JutsuNoticeEntry sampleNoticeEntry(String slug, int episode) {
        return new JutsuNoticeEntry(
                slug,
                1,
                Math.max(1, episode),
                slug + ": " + episode,
                "https://jut.su/" + slug + "/episode-" + Math.max(1, episode) + ".html",
                null,
                "сегодня");
    }
}
