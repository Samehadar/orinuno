package com.orinuno.service.calendar;

import com.orinuno.client.calendar.CalendarFetchResult;
import com.orinuno.client.calendar.KodikCalendarHttpClient;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.configuration.ReferenceCacheConfig;
import com.orinuno.model.dto.CalendarResponse;
import com.orinuno.model.dto.CalendarResponse.EnrichedCalendarEntryDto;
import com.orinuno.repository.ContentRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Public surface for the Kodik calendar dump (IDEA-AP-5). Layers a Caffeine cache (5-minute TTL,
 * see {@link ReferenceCacheConfig}) over the HTTP fetcher and applies optional filtering and
 * Shikimori-id enrichment against {@code kodik_content}.
 *
 * <p>The cache key is the literal string {@code "raw"} because we always cache the full raw fetch —
 * filtering happens after the cache hit so that one upstream call can serve many filter shapes.
 */
@Slf4j
@Service
public class KodikCalendarService {

    private final KodikCalendarHttpClient httpClient;
    private final ContentRepository contentRepository;

    public KodikCalendarService(
            KodikCalendarHttpClient httpClient, ContentRepository contentRepository) {
        this.httpClient = httpClient;
        this.contentRepository = contentRepository;
    }

    /**
     * Cached gateway to the upstream dump. Returns a {@link Mono} so callers can subscribe with
     * their own scheduling, but the inner fetch is materialized synchronously inside the cache so
     * the second concurrent caller sees the same {@link CalendarFetchResult}.
     */
    @Cacheable(cacheNames = ReferenceCacheConfig.CALENDAR, key = "'raw'", sync = true)
    public CalendarFetchResult getRawCached() {
        return httpClient.fetch().block();
    }

    /**
     * Fetch (cached) → filter → optionally enrich with orinuno content ids. Returns a deterministic
     * {@link CalendarResponse} — entries preserve upstream order.
     */
    public CalendarResponse get(CalendarFilter filter, boolean enrich) {
        CalendarFetchResult raw = getRawCached();
        List<KodikCalendarEntryDto> filtered = applyFilter(raw.entries(), filter);
        Map<String, Long> shikimoriToContentId =
                enrich ? buildEnrichmentIndex(filtered) : Collections.emptyMap();
        List<EnrichedCalendarEntryDto> entries = new ArrayList<>(filtered.size());
        for (KodikCalendarEntryDto entry : filtered) {
            Long contentId = null;
            if (enrich && entry.anime() != null && entry.anime().id() != null) {
                contentId = shikimoriToContentId.get(entry.anime().id());
            }
            entries.add(new EnrichedCalendarEntryDto(entry, contentId));
        }
        return new CalendarResponse(raw.fetchedAt(), raw.etag(), entries.size(), entries);
    }

    /** Bypasses the application cache. Intended for admin reload paths. */
    public CalendarFetchResult forceRefresh() {
        return httpClient.fetch().block();
    }

    static List<KodikCalendarEntryDto> applyFilter(
            List<KodikCalendarEntryDto> entries, CalendarFilter filter) {
        if (filter == null
                || (filter.status() == null
                        && filter.kind() == null
                        && filter.minScore() == null
                        && filter.limit() == null)) {
            return entries;
        }
        List<KodikCalendarEntryDto> out = new ArrayList<>(entries.size());
        for (KodikCalendarEntryDto entry : entries) {
            if (filter.status() != null && !filter.status().equalsIgnoreCase(entry.status())) {
                continue;
            }
            if (filter.kind() != null && !filter.kind().equalsIgnoreCase(entry.kind())) {
                continue;
            }
            if (filter.minScore() != null) {
                Double score = entry.score();
                if (score == null || score < filter.minScore()) {
                    continue;
                }
            }
            out.add(entry);
            if (filter.limit() != null && out.size() >= filter.limit()) {
                break;
            }
        }
        return out;
    }

    private Map<String, Long> buildEnrichmentIndex(List<KodikCalendarEntryDto> entries) {
        List<String> shikimoriIds = new ArrayList<>(entries.size());
        for (KodikCalendarEntryDto entry : entries) {
            if (entry.anime() != null && entry.anime().id() != null) {
                shikimoriIds.add(entry.anime().id());
            }
        }
        if (shikimoriIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Map<String, Object>> rows = contentRepository.findIdsByShikimoriIds(shikimoriIds);
        Map<String, Long> index = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object shikimori = row.get("shikimoriId");
            Object id = row.get("id");
            if (shikimori != null && id instanceof Number n) {
                index.put(shikimori.toString(), n.longValue());
            }
        }
        log.debug(
                "Calendar enrichment: {} shikimori ids → {} matches",
                shikimoriIds.size(),
                index.size());
        return index;
    }
}
