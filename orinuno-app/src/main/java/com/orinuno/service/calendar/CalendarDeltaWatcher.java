package com.orinuno.service.calendar;

import com.orinuno.client.calendar.CalendarFetchResult;
import com.orinuno.client.calendar.KodikCalendarHttpClient;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikCalendarOutboxEvent;
import com.orinuno.model.KodikCalendarState;
import com.orinuno.repository.KodikCalendarOutboxRepository;
import com.orinuno.repository.KodikCalendarStateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CAL-6 — diffs every Kodik calendar fetch against the previously persisted state and emits one
 * {@link com.orinuno.model.KodikCalendarOutboxEvent} per detected delta.
 *
 * <p>Triggered by {@link CalendarDeltaScheduler} (default cadence: 5 min, matching Kodik's calendar
 * refresh rate). Reads the current calendar via {@link KodikCalendarHttpClient#fetch()} (which
 * already does conditional GET, so this is cheap on quiet windows), bulk-loads the matching state
 * rows, computes deltas in memory, then writes outbox events + upserts the state in a single
 * synchronous loop. Runs on the boundedElastic scheduler in {@link CalendarDeltaScheduler}, so
 * MyBatis blocking calls are allowed.
 *
 * <p>The watcher is idempotent across reruns: {@link #processFetch(CalendarFetchResult)} is safe to
 * invoke multiple times for the same fetch — entries that already match the stored state produce no
 * new events. This keeps the outbox stable when the scheduler retries after a transient DB outage.
 */
@Slf4j
@Component
public class CalendarDeltaWatcher {

    private final KodikCalendarHttpClient httpClient;
    private final KodikCalendarStateRepository stateRepository;
    private final KodikCalendarOutboxRepository outboxRepository;
    private final OrinunoProperties properties;

    public CalendarDeltaWatcher(
            KodikCalendarHttpClient httpClient,
            KodikCalendarStateRepository stateRepository,
            KodikCalendarOutboxRepository outboxRepository,
            OrinunoProperties properties) {
        this.httpClient = httpClient;
        this.stateRepository = stateRepository;
        this.outboxRepository = outboxRepository;
        this.properties = properties;
    }

    /** Scheduler entrypoint. Returns the count of detected deltas this cycle. */
    public int runOnce() {
        if (!properties.getCalendar().isEnabled()) {
            log.debug("CAL-6: calendar disabled, skipping watcher cycle");
            return 0;
        }
        try {
            CalendarFetchResult fetch = httpClient.fetch().block();
            if (fetch == null) {
                log.warn("CAL-6: fetch returned null result");
                return 0;
            }
            return processFetch(fetch);
        } catch (Exception ex) {
            log.warn(
                    "CAL-6: watcher cycle failed ({}: {})",
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return 0;
        }
    }

    /**
     * Visible for tests / manual triggers. Runs the whole diff + persist pipeline on a fully
     * materialised fetch result. Returns the number of outbox events written.
     */
    public int processFetch(CalendarFetchResult fetch) {
        if (fetch == null || fetch.entries() == null || fetch.entries().isEmpty()) {
            return 0;
        }
        Map<String, KodikCalendarState> existing = loadExistingStates(fetch.entries());
        LocalDateTime now = LocalDateTime.now();

        int eventCount = 0;
        for (KodikCalendarEntryDto entry : fetch.entries()) {
            if (entry.anime() == null || entry.anime().id() == null) {
                continue;
            }
            String shikimoriId = entry.anime().id();
            KodikCalendarState newState = toState(entry, now);
            KodikCalendarState prior = existing.get(shikimoriId);

            List<KodikCalendarOutboxEvent> deltas = diff(prior, newState, now);
            for (KodikCalendarOutboxEvent event : deltas) {
                outboxRepository.insert(event);
                eventCount++;
            }
            stateRepository.upsert(newState);
        }
        if (eventCount > 0) {
            log.info(
                    "CAL-6: detected {} calendar deltas across {} entries",
                    eventCount,
                    fetch.entries().size());
        }
        return eventCount;
    }

    private Map<String, KodikCalendarState> loadExistingStates(
            List<KodikCalendarEntryDto> entries) {
        List<String> ids = new ArrayList<>(entries.size());
        for (KodikCalendarEntryDto entry : entries) {
            if (entry.anime() != null && entry.anime().id() != null) {
                ids.add(entry.anime().id());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<KodikCalendarState> rows = stateRepository.findByShikimoriIds(ids);
            Map<String, KodikCalendarState> out = new HashMap<>(rows.size());
            for (KodikCalendarState row : rows) {
                out.put(row.getShikimoriId(), row);
            }
            return out;
        } catch (Exception ex) {
            log.warn(
                    "CAL-6: failed to bulk-load existing calendar state ({}), all entries will be"
                            + " treated as NEW_ANIME this cycle",
                    ex.toString());
            return Map.of();
        }
    }

    static KodikCalendarState toState(KodikCalendarEntryDto entry, LocalDateTime now) {
        return KodikCalendarState.builder()
                .shikimoriId(entry.anime().id())
                .nextEpisode(entry.nextEpisode())
                .nextEpisodeAt(toLocal(entry.nextEpisodeAt()))
                .episodesAired(entry.episodesAired())
                .episodesTotal(entry.episodes())
                .status(entry.status())
                .score(entry.score() == null ? null : roundedScore(entry.score()))
                .kind(entry.kind())
                .airedOn(entry.airedOn())
                .releasedOn(entry.releasedOn())
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    static List<KodikCalendarOutboxEvent> diff(
            KodikCalendarState prior, KodikCalendarState next, LocalDateTime detectedAt) {
        List<KodikCalendarOutboxEvent> out = new ArrayList<>();
        if (prior == null) {
            out.add(
                    event(
                            next.getShikimoriId(),
                            CalendarChangeType.NEW_ANIME,
                            null,
                            describe(next),
                            detectedAt));
            return out;
        }
        if (advanced(prior.getNextEpisode(), next.getNextEpisode())) {
            out.add(
                    event(
                            next.getShikimoriId(),
                            CalendarChangeType.NEXT_EPISODE_ADVANCED,
                            String.valueOf(prior.getNextEpisode()),
                            String.valueOf(next.getNextEpisode()),
                            detectedAt));
        } else if (rescheduled(prior.getNextEpisodeAt(), next.getNextEpisodeAt())) {
            out.add(
                    event(
                            next.getShikimoriId(),
                            CalendarChangeType.NEXT_EPISODE_RESCHEDULED,
                            stringify(prior.getNextEpisodeAt()),
                            stringify(next.getNextEpisodeAt()),
                            detectedAt));
        }
        if (advanced(prior.getEpisodesAired(), next.getEpisodesAired())) {
            out.add(
                    event(
                            next.getShikimoriId(),
                            CalendarChangeType.EPISODES_AIRED_INCREASED,
                            String.valueOf(prior.getEpisodesAired()),
                            String.valueOf(next.getEpisodesAired()),
                            detectedAt));
        }
        if (changed(prior.getStatus(), next.getStatus())) {
            out.add(
                    event(
                            next.getShikimoriId(),
                            CalendarChangeType.STATUS_CHANGED,
                            prior.getStatus(),
                            next.getStatus(),
                            detectedAt));
        }
        if (changed(prior.getScore(), next.getScore())) {
            out.add(
                    event(
                            next.getShikimoriId(),
                            CalendarChangeType.SCORE_CHANGED,
                            stringify(prior.getScore()),
                            stringify(next.getScore()),
                            detectedAt));
        }
        if (prior.getReleasedOn() == null && next.getReleasedOn() != null) {
            out.add(
                    event(
                            next.getShikimoriId(),
                            CalendarChangeType.RELEASED_ON_SET,
                            null,
                            next.getReleasedOn().toString(),
                            detectedAt));
        }
        return out;
    }

    private static KodikCalendarOutboxEvent event(
            String shikimoriId,
            CalendarChangeType type,
            String oldValue,
            String newValue,
            LocalDateTime detectedAt) {
        return KodikCalendarOutboxEvent.builder()
                .shikimoriId(shikimoriId)
                .changeType(type.name())
                .oldValue(oldValue)
                .newValue(newValue)
                .detectedAt(detectedAt)
                .build();
    }

    private static boolean advanced(Integer prior, Integer next) {
        if (next == null) return false;
        if (prior == null) return true;
        return next > prior;
    }

    private static boolean rescheduled(LocalDateTime prior, LocalDateTime next) {
        return changed(prior, next);
    }

    private static boolean changed(Object prior, Object next) {
        return !Objects.equals(prior, next);
    }

    private static String describe(KodikCalendarState s) {
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(Optional.ofNullable(s.getStatus()).orElse("?"));
        sb.append(",episodes=").append(Optional.ofNullable(s.getEpisodesAired()).orElse(0));
        sb.append("/").append(Optional.ofNullable(s.getEpisodesTotal()).orElse(0));
        if (s.getNextEpisode() != null) {
            sb.append(",nextEp=").append(s.getNextEpisode());
        }
        return truncate(sb.toString(), 255);
    }

    private static String stringify(Object v) {
        if (v == null) return null;
        return truncate(v.toString(), 255);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BigDecimal roundedScore(Double score) {
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }
}
