package com.orinuno.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.calendar.CalendarFetchResult;
import com.orinuno.client.calendar.KodikCalendarHttpClient;
import com.orinuno.client.dto.calendar.KodikCalendarAnimeDto;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.client.dto.calendar.KodikCalendarImageDto;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikCalendarOutboxEvent;
import com.orinuno.model.KodikCalendarState;
import com.orinuno.repository.KodikCalendarOutboxRepository;
import com.orinuno.repository.KodikCalendarStateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * CAL-6 — pins the diff semantics of {@link CalendarDeltaWatcher}: which fields trigger an outbox
 * event, idempotency on re-runs, and graceful absorption of the bulk-load fast path.
 */
class CalendarDeltaWatcherTest {

    private KodikCalendarHttpClient httpClient;
    private KodikCalendarStateRepository stateRepo;
    private KodikCalendarOutboxRepository outboxRepo;
    private OrinunoProperties properties;
    private CalendarDeltaWatcher watcher;

    @BeforeEach
    void setUp() {
        httpClient = mock(KodikCalendarHttpClient.class);
        stateRepo = mock(KodikCalendarStateRepository.class);
        outboxRepo = mock(KodikCalendarOutboxRepository.class);
        properties = new OrinunoProperties();
        properties.getCalendar().setEnabled(true);
        watcher = new CalendarDeltaWatcher(httpClient, stateRepo, outboxRepo, properties);
    }

    @Test
    @DisplayName("first sighting of an anime emits NEW_ANIME and upserts the state row")
    void newAnimeEmitsNewEvent() {
        KodikCalendarEntryDto entry = entry("alpha", 3, "ongoing", 9.5, 12, 2);
        when(stateRepo.findByShikimoriIds(anyList())).thenReturn(List.of());

        int events = watcher.processFetch(fetch(entry));

        assertThat(events).isEqualTo(1);
        ArgumentCaptor<KodikCalendarOutboxEvent> captor =
                ArgumentCaptor.forClass(KodikCalendarOutboxEvent.class);
        verify(outboxRepo, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getChangeType())
                .isEqualTo(CalendarChangeType.NEW_ANIME.name());
        assertThat(captor.getValue().getShikimoriId()).isEqualTo("alpha");
        verify(stateRepo, times(1)).upsert(any(KodikCalendarState.class));
    }

    @Test
    @DisplayName("rerun on an unchanged entry produces zero outbox events")
    void unchangedEntryIsIdempotent() {
        KodikCalendarEntryDto entry = entry("alpha", 3, "ongoing", 9.5, 12, 2);
        KodikCalendarState prior = state("alpha", 3, "ongoing", 9.5, 12, 2);
        when(stateRepo.findByShikimoriIds(anyList())).thenReturn(List.of(prior));

        int events = watcher.processFetch(fetch(entry));

        assertThat(events).isZero();
        verify(outboxRepo, never()).insert(any());
        verify(stateRepo, times(1)).upsert(any());
    }

    @Test
    @DisplayName("nextEpisode advancing yields NEXT_EPISODE_ADVANCED")
    void nextEpisodeAdvanceEvent() {
        KodikCalendarEntryDto entry = entry("alpha", 4, "ongoing", 9.5, 12, 3);
        KodikCalendarState prior = state("alpha", 3, "ongoing", 9.5, 12, 2);
        when(stateRepo.findByShikimoriIds(anyList())).thenReturn(List.of(prior));

        int events = watcher.processFetch(fetch(entry));

        assertThat(events).isEqualTo(2);
        ArgumentCaptor<KodikCalendarOutboxEvent> captor =
                ArgumentCaptor.forClass(KodikCalendarOutboxEvent.class);
        verify(outboxRepo, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(KodikCalendarOutboxEvent::getChangeType)
                .containsExactly(
                        CalendarChangeType.NEXT_EPISODE_ADVANCED.name(),
                        CalendarChangeType.EPISODES_AIRED_INCREASED.name());
    }

    @Test
    @DisplayName("status change from ongoing to released yields STATUS_CHANGED")
    void statusChangeEvent() {
        KodikCalendarEntryDto entry = entry("alpha", 12, "released", 9.5, 12, 12);
        KodikCalendarState prior = state("alpha", 12, "ongoing", 9.5, 12, 12);
        when(stateRepo.findByShikimoriIds(anyList())).thenReturn(List.of(prior));

        int events = watcher.processFetch(fetch(entry));

        assertThat(events).isEqualTo(1);
        ArgumentCaptor<KodikCalendarOutboxEvent> captor =
                ArgumentCaptor.forClass(KodikCalendarOutboxEvent.class);
        verify(outboxRepo, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getChangeType())
                .isEqualTo(CalendarChangeType.STATUS_CHANGED.name());
        assertThat(captor.getValue().getOldValue()).isEqualTo("ongoing");
        assertThat(captor.getValue().getNewValue()).isEqualTo("released");
    }

    @Test
    @DisplayName("score change yields SCORE_CHANGED with rounded values")
    void scoreChangeEvent() {
        KodikCalendarEntryDto entry = entry("alpha", 3, "ongoing", 9.567, 12, 2);
        KodikCalendarState prior = state("alpha", 3, "ongoing", 9.5, 12, 2);
        when(stateRepo.findByShikimoriIds(anyList())).thenReturn(List.of(prior));

        int events = watcher.processFetch(fetch(entry));

        assertThat(events).isEqualTo(1);
        ArgumentCaptor<KodikCalendarOutboxEvent> captor =
                ArgumentCaptor.forClass(KodikCalendarOutboxEvent.class);
        verify(outboxRepo, times(1)).insert(captor.capture());
        KodikCalendarOutboxEvent ev = captor.getValue();
        assertThat(ev.getChangeType()).isEqualTo(CalendarChangeType.SCORE_CHANGED.name());
        assertThat(ev.getNewValue()).isEqualTo("9.57");
    }

    @Test
    @DisplayName("processFetch tolerates entries without anime id (they are skipped)")
    void entriesWithoutAnimeAreSkipped() {
        KodikCalendarEntryDto bad =
                new KodikCalendarEntryDto(
                        1, null, 24, null, "tv", 9.0, "ongoing", 12, 1, null, null);
        when(stateRepo.findByShikimoriIds(anyList())).thenReturn(List.of());

        int events = watcher.processFetch(fetch(bad));

        assertThat(events).isZero();
        verify(outboxRepo, never()).insert(any());
        verify(stateRepo, never()).upsert(any());
    }

    @Test
    @DisplayName("runOnce short-circuits when calendar is disabled")
    void runOnceShortCircuitsWhenCalendarDisabled() {
        properties.getCalendar().setEnabled(false);

        int events = watcher.runOnce();

        assertThat(events).isZero();
        verify(httpClient, never()).fetch();
    }

    @Test
    @DisplayName("processFetch returns 0 on null/empty fetch")
    void emptyFetchIsNoOp() {
        assertThat(watcher.processFetch(null)).isZero();
        assertThat(
                        watcher.processFetch(
                                new CalendarFetchResult(Instant.now(), null, null, List.of())))
                .isZero();
    }

    @Test
    @DisplayName("toState rounds Shikimori scores to 2 decimal places")
    void toStateRoundsScore() {
        KodikCalendarState s =
                CalendarDeltaWatcher.toState(
                        entry("alpha", 1, "ongoing", 9.876, 12, 0), LocalDateTime.now());
        assertThat(s.getScore()).isEqualByComparingTo(new BigDecimal("9.88"));
    }

    @Test
    @DisplayName("toState converts UTC instant nextEpisodeAt to LocalDateTime in UTC")
    void toStateConvertsInstantToLocalDateTime() {
        Instant instant = Instant.parse("2026-05-02T10:00:00Z");
        KodikCalendarEntryDto entry =
                new KodikCalendarEntryDto(
                        3,
                        instant,
                        24,
                        new KodikCalendarAnimeDto(
                                "alpha",
                                "name",
                                "russian",
                                new KodikCalendarImageDto(null, null, null, null, null)),
                        "tv",
                        9.0,
                        "ongoing",
                        12,
                        2,
                        null,
                        null);

        KodikCalendarState s = CalendarDeltaWatcher.toState(entry, LocalDateTime.now());

        assertThat(s.getNextEpisodeAt())
                .isEqualTo(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("releasedOn becoming non-null yields RELEASED_ON_SET")
    void releasedOnSetEvent() {
        KodikCalendarEntryDto entry =
                entryWithReleased("alpha", 12, "released", LocalDate.of(2026, 5, 2));
        KodikCalendarState prior = state("alpha", 12, "released", 9.5, 12, 12);
        prior.setReleasedOn(null);
        when(stateRepo.findByShikimoriIds(anyList())).thenReturn(List.of(prior));

        watcher.processFetch(fetch(entry));

        ArgumentCaptor<KodikCalendarOutboxEvent> captor =
                ArgumentCaptor.forClass(KodikCalendarOutboxEvent.class);
        verify(outboxRepo, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getChangeType())
                .isEqualTo(CalendarChangeType.RELEASED_ON_SET.name());
        assertThat(captor.getValue().getNewValue()).isEqualTo("2026-05-02");
    }

    @Test
    @DisplayName("diff helpers return Optional.empty when prior matches next")
    void diffEmptyWhenIdentical() {
        LocalDateTime now = LocalDateTime.now();
        KodikCalendarState prior = state("alpha", 3, "ongoing", 9.5, 12, 2);
        KodikCalendarState next = state("alpha", 3, "ongoing", 9.5, 12, 2);
        assertThat(CalendarDeltaWatcher.diff(prior, next, now)).isEmpty();
    }

    @Test
    @DisplayName("diff returns Optional results for null fields gracefully")
    void diffHandlesNullEpisodeFields() {
        LocalDateTime now = LocalDateTime.now();
        KodikCalendarState prior = state("alpha", null, "ongoing", null, 12, null);
        KodikCalendarState next = state("alpha", null, "ongoing", null, 12, null);
        assertThat(CalendarDeltaWatcher.diff(prior, next, now)).isEmpty();
    }

    private static CalendarFetchResult fetch(KodikCalendarEntryDto... entries) {
        return new CalendarFetchResult(Instant.now(), "etag", "lm", List.of(entries));
    }

    private static KodikCalendarEntryDto entry(
            String id, Integer nextEp, String status, Double score, Integer total, Integer aired) {
        return new KodikCalendarEntryDto(
                nextEp,
                Instant.parse("2026-05-02T10:00:00Z"),
                24,
                new KodikCalendarAnimeDto(
                        id,
                        "name",
                        "russian",
                        new KodikCalendarImageDto(null, null, null, null, null)),
                "tv",
                score,
                status,
                total,
                aired,
                null,
                null);
    }

    private static KodikCalendarEntryDto entryWithReleased(
            String id, Integer aired, String status, LocalDate releasedOn) {
        return new KodikCalendarEntryDto(
                aired,
                Instant.parse("2026-05-02T10:00:00Z"),
                24,
                new KodikCalendarAnimeDto(
                        id,
                        "name",
                        "russian",
                        new KodikCalendarImageDto(null, null, null, null, null)),
                "tv",
                9.5,
                status,
                aired,
                aired,
                null,
                releasedOn);
    }

    private static KodikCalendarState state(
            String id, Integer nextEp, String status, Double score, Integer total, Integer aired) {
        return KodikCalendarState.builder()
                .shikimoriId(id)
                .nextEpisode(nextEp)
                .nextEpisodeAt(LocalDateTime.parse("2026-05-02T10:00:00"))
                .episodesAired(aired)
                .episodesTotal(total)
                .status(status)
                .score(
                        score == null
                                ? null
                                : new BigDecimal(String.valueOf(score))
                                        .setScale(2, java.math.RoundingMode.HALF_UP))
                .kind("tv")
                .firstSeenAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .build();
    }

    @SuppressWarnings("unused")
    private static Optional<KodikCalendarState> opt(KodikCalendarState s) {
        return Optional.ofNullable(s);
    }
}
