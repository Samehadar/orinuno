package com.orinuno.jutsu.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.info.JutsuEpisodeListing;
import com.orinuno.jutsu.info.JutsuSeason;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuSyncState;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
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
 * Unit-level coverage for the notice-feed incremental walker. We mock the SDK + repos so we can
 * rehearse the first-tick / idle / catch-up / new-slug / fetch-info branches without booting MySQL
 * or hitting live jut.su.
 */
@ExtendWith(MockitoExtension.class)
class JutsuCatalogSyncServiceNoticeWalkTest {

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
        properties.getProviders().getJutsu().getSync().getNoticeWalk().setEnabled(true);
        properties.getProviders().getJutsu().getSync().getNoticeWalk().setMaxFeedsPerTick(5);
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
    @DisplayName("disabled notice-walk short-circuits without touching SDK or repos")
    void disabledShortCircuits() {
        properties.getProviders().getJutsu().getSync().getNoticeWalk().setEnabled(false);

        JutsuCatalogSyncService.NoticeWalkResult result = service.runNoticeWalkOnce(5, 5);

        assertThat(result.feedsWalked()).isZero();
        assertThat(result.uniqueSlugsDiscovered()).isZero();
        verify(client, never()).getLatestNoticeFeed();
        verify(syncStateRepository, never()).update(any());
    }

    @Test
    @DisplayName(
            "first-ever tick (savedCursor=null) only records the discovered cursor — never"
                    + " backfills the entire notice history")
    void firstTickRecordsCursorWithoutBackfill() {
        when(syncStateRepository.findSingleton())
                .thenReturn(Optional.of(JutsuSyncState.empty(LocalDateTime.now())));
        when(client.getLatestNoticeFeed())
                .thenReturn(Mono.just(feed(20000, entry("naruto-shippuuden", 1, 5, "x.jpg"))));

        JutsuCatalogSyncService.NoticeWalkResult result = service.runNoticeWalkOnce(5, 0);

        assertThat(result.previousCursor()).isNull();
        assertThat(result.newCursor()).isEqualTo(20000);
        assertThat(result.uniqueSlugsDiscovered()).isZero();
        assertThat(result.feedsWalked()).isZero();
        verify(client, never()).getNoticeFeed(anyInt());
        verify(titleRepository, never()).upsert(any());

        ArgumentCaptor<JutsuSyncState> stateCaptor = ArgumentCaptor.forClass(JutsuSyncState.class);
        verify(syncStateRepository).update(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getNoticeCursor()).isEqualTo(20000);
    }

    @Test
    @DisplayName("idle tick (discovered <= savedCursor) just touches noticeLastWalkedAt and exits")
    void idleTickIsCheap() {
        JutsuSyncState state = JutsuSyncState.empty(LocalDateTime.now());
        state.setNoticeCursor(20000);
        when(syncStateRepository.findSingleton()).thenReturn(Optional.of(state));
        when(client.getLatestNoticeFeed()).thenReturn(Mono.just(feed(20000)));

        JutsuCatalogSyncService.NoticeWalkResult result = service.runNoticeWalkOnce(5, 0);

        assertThat(result.previousCursor()).isEqualTo(20000);
        assertThat(result.newCursor()).isEqualTo(20000);
        assertThat(result.uniqueSlugsDiscovered()).isZero();
        verify(client, never()).getNoticeFeed(anyInt());
        verify(titleRepository, never()).upsert(any());
    }

    @Test
    @DisplayName(
            "fetch-info-on-discovery=false writes a slug+thumbnail placeholder for new slugs and"
                    + " skips known ones")
    void newSlugsBecomePlaceholdersByDefault() {
        JutsuSyncState state = JutsuSyncState.empty(LocalDateTime.now());
        state.setNoticeCursor(19990);
        when(syncStateRepository.findSingleton()).thenReturn(Optional.of(state));
        // Latest feed: 3 entries, 2 distinct slugs, only one of which we already know.
        when(client.getLatestNoticeFeed())
                .thenReturn(
                        Mono.just(
                                feed(
                                        19992,
                                        entry("naruto", 1, 5, "naruto.jpg"),
                                        entry("naruto", 1, 6, "naruto.jpg"),
                                        entry("bleach", 1, 200, "bleach.jpg"))));
        when(titleRepository.findBySlugs(anyList()))
                .thenReturn(List.of(JutsuTitle.builder().slug("naruto").build()));

        JutsuCatalogSyncService.NoticeWalkResult result = service.runNoticeWalkOnce(5, 0);

        assertThat(result.feedsWalked()).isEqualTo(1);
        assertThat(result.uniqueSlugsDiscovered()).isEqualTo(2);
        assertThat(result.newInfoFetched()).isZero();
        assertThat(result.newPlaceholdersWritten()).isEqualTo(1);
        assertThat(result.previousCursor()).isEqualTo(19990);
        assertThat(result.newCursor()).isEqualTo(19992);

        ArgumentCaptor<JutsuTitle> titleCaptor = ArgumentCaptor.forClass(JutsuTitle.class);
        verify(titleRepository, times(1)).upsert(titleCaptor.capture());
        JutsuTitle written = titleCaptor.getValue();
        assertThat(written.getSlug()).isEqualTo("bleach");
        assertThat(written.getThumbnailUrl()).isEqualTo("bleach.jpg");
        verify(client, never()).getAnimeInfo(anyString());
    }

    @Test
    @DisplayName(
            "fetch-info-on-discovery=true hydrates new slugs from getAnimeInfo and bulk-upserts"
                    + " their episode lists")
    void fetchInfoOnDiscoveryHydratesNewSlugs() {
        properties
                .getProviders()
                .getJutsu()
                .getSync()
                .getNoticeWalk()
                .setFetchInfoOnDiscovery(true);
        properties.getProviders().getJutsu().getSync().getNoticeWalk().setMaxInfoFetchesPerTick(5);

        JutsuSyncState state = JutsuSyncState.empty(LocalDateTime.now());
        state.setNoticeCursor(19990);
        when(syncStateRepository.findSingleton()).thenReturn(Optional.of(state));
        when(client.getLatestNoticeFeed())
                .thenReturn(Mono.just(feed(19991, entry("brand-new", 1, 1, "thumb.jpg"))));
        when(titleRepository.findBySlugs(anyList())).thenReturn(List.of());
        when(client.getAnimeInfo(eq("brand-new")))
                .thenReturn(
                        Mono.just(
                                new JutsuAnimeInfo(
                                        "brand-new",
                                        "Бренд новое",
                                        "Brand New",
                                        "Описание",
                                        Optional.empty(),
                                        Set.of(),
                                        Set.of(),
                                        "thumb.jpg",
                                        List.of(
                                                new JutsuSeason(
                                                        1,
                                                        "1 сезон",
                                                        List.of(
                                                                new JutsuEpisodeListing(
                                                                        "brand-new",
                                                                        1,
                                                                        1,
                                                                        "1 серия",
                                                                        "/brand-new/episode-1.html"),
                                                                new JutsuEpisodeListing(
                                                                        "brand-new",
                                                                        1,
                                                                        2,
                                                                        "2 серия",
                                                                        "/brand-new/episode-2.html")))))));

        JutsuCatalogSyncService.NoticeWalkResult result = service.runNoticeWalkOnce(5, 5);

        assertThat(result.newInfoFetched()).isEqualTo(1);
        assertThat(result.newPlaceholdersWritten()).isZero();

        ArgumentCaptor<JutsuTitle> titleCaptor = ArgumentCaptor.forClass(JutsuTitle.class);
        verify(titleRepository).upsert(titleCaptor.capture());
        JutsuTitle written = titleCaptor.getValue();
        assertThat(written.getSlug()).isEqualTo("brand-new");
        assertThat(written.getTitle()).isEqualTo("Бренд новое");
        assertThat(written.getSynopsis()).isEqualTo("Описание");
        assertThat(written.getInfoTotalSeasons()).isEqualTo(1);
        assertThat(written.getInfoTotalEpisodes()).isEqualTo(2);
        assertThat(written.getInfoFetchedAt()).isNotNull();
        assertThat(written.getCatalogFetchedAt())
                .as("info-page hydration must NOT touch catalog-only fields")
                .isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JutsuEpisode>> episodeCaptor = ArgumentCaptor.forClass(List.class);
        verify(episodeRepository).upsertAll(episodeCaptor.capture());
        assertThat(episodeCaptor.getValue()).hasSize(2);
        assertThat(episodeCaptor.getValue().get(0).getSlug()).isEqualTo("brand-new");
        assertThat(episodeCaptor.getValue().get(0).getEpisode()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "walk loop stops once the oldest entry of the current feed is at or below"
                    + " savedCursor — bounded backfill")
    void walkStopsAtSavedCursor() {
        JutsuSyncState state = JutsuSyncState.empty(LocalDateTime.now());
        state.setNoticeCursor(19000);
        when(syncStateRepository.findSingleton()).thenReturn(Optional.of(state));

        // First feed at cursor 19010 has 5 entries (oldest id = 19006). 19006 > 19000 + 1 = 19001
        // → should walk to next.
        // Second feed at cursor 19005 has 6 entries (oldest = 19000). 19000 <= 19000 + 1 = 19001
        // → loop must stop after reading this feed.
        // Third feed (19004) must NOT be requested.
        when(client.getLatestNoticeFeed())
                .thenReturn(
                        Mono.just(
                                feed(
                                        19010,
                                        entry("a", 1, 1, null),
                                        entry("b", 1, 1, null),
                                        entry("c", 1, 1, null),
                                        entry("d", 1, 1, null),
                                        entry("e", 1, 1, null))));
        when(client.getNoticeFeed(eq(19005)))
                .thenReturn(
                        Mono.just(
                                feed(
                                        19005,
                                        entry("f", 1, 1, null),
                                        entry("g", 1, 1, null),
                                        entry("h", 1, 1, null),
                                        entry("i", 1, 1, null),
                                        entry("j", 1, 1, null),
                                        entry("k", 1, 1, null))));
        when(titleRepository.findBySlugs(anyList())).thenReturn(List.of());

        JutsuCatalogSyncService.NoticeWalkResult result = service.runNoticeWalkOnce(5, 0);

        assertThat(result.feedsWalked()).isEqualTo(2);
        assertThat(result.uniqueSlugsDiscovered()).isEqualTo(11);
        verify(client, times(1)).getLatestNoticeFeed();
        verify(client, times(1)).getNoticeFeed(19005);
        verify(client, never()).getNoticeFeed(19004);
    }

    @Test
    @DisplayName("getLatestNoticeFeed failure records lastError and aborts the tick")
    void discoveryFailureRecordsLastError() {
        JutsuSyncState state = JutsuSyncState.empty(LocalDateTime.now());
        state.setNoticeCursor(19000);
        when(syncStateRepository.findSingleton()).thenReturn(Optional.of(state));
        when(client.getLatestNoticeFeed())
                .thenReturn(Mono.error(new IllegalStateException("homepage 503")));

        JutsuCatalogSyncService.NoticeWalkResult result = service.runNoticeWalkOnce(5, 0);

        assertThat(result.error()).contains("homepage 503");
        assertThat(result.feedsWalked()).isZero();
        assertThat(result.uniqueSlugsDiscovered()).isZero();
        verify(titleRepository, never()).upsert(any());

        ArgumentCaptor<JutsuSyncState> stateCaptor = ArgumentCaptor.forClass(JutsuSyncState.class);
        verify(syncStateRepository).update(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastError()).contains("homepage 503");
        assertThat(stateCaptor.getValue().getNoticeCursor())
                .as("discovery failure must NOT advance the cursor")
                .isEqualTo(19000);
    }

    @Test
    @DisplayName(
            "noticeToPlaceholderTitle strips the trailing ': N серия' and falls back to slug when"
                    + " the title is malformed")
    void placeholderTitleStripsEpisodeSuffix() {
        JutsuTitle row =
                JutsuCatalogSyncService.noticeToPlaceholderTitle(
                        new JutsuNoticeEntry(
                                "kowloon",
                                1,
                                7,
                                "Девять небес: 7 серия",
                                "https://jut.su/kowloon/season-1/episode-7.html",
                                "thumb.jpg",
                                "сегодня"),
                        "kowloon",
                        LocalDateTime.now());

        assertThat(row.getSlug()).isEqualTo("kowloon");
        assertThat(row.getTitle()).isEqualTo("Девять небес");
        assertThat(row.getThumbnailUrl()).isEqualTo("thumb.jpg");
        assertThat(row.getSynopsis()).isNull();
        assertThat(row.getCatalogFetchedAt()).isNull();
    }

    private static JutsuNoticeFeed feed(int cursor, JutsuNoticeEntry... entries) {
        return new JutsuNoticeFeed(cursor, List.of(entries));
    }

    private static JutsuNoticeEntry entry(String slug, int season, int episode, String thumb) {
        return new JutsuNoticeEntry(
                slug,
                season,
                episode,
                slug + ": " + episode + " серия",
                "https://jut.su/" + slug + "/episode-" + episode + ".html",
                thumb,
                "сегодня");
    }
}
