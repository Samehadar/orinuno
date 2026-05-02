package com.orinuno.controller;

import com.orinuno.model.KodikCalendarOutboxEvent;
import com.orinuno.model.dto.CalendarResponse;
import com.orinuno.repository.KodikCalendarOutboxRepository;
import com.orinuno.service.calendar.CalendarFilter;
import com.orinuno.service.calendar.KodikCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Public on-demand surface for the Kodik calendar dump (IDEA-AP-5). Wraps {@link
 * KodikCalendarService} with a thin reactive shell so that {@code @Cacheable} (which is blocking)
 * runs on {@link Schedulers#boundedElastic()}.
 *
 * <p>Filtering parameters are optional and combinable. Set {@code enrich=true} to include the
 * orinuno {@code content_id} for entries whose Shikimori id matches a row in {@code kodik_content}
 * — used by the demo to deep-link from the calendar widget into existing content pages.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
@Tag(name = "Calendar", description = "Public Kodik calendar dump (premiere schedule)")
public class KodikCalendarController {

    private static final int OUTBOX_LIMIT_MAX = 1_000;
    private static final int OUTBOX_LIMIT_DEFAULT = 200;

    private final KodikCalendarService calendarService;
    private final KodikCalendarOutboxRepository outboxRepository;

    @GetMapping
    @Operation(
            summary = "Fetch the public Kodik calendar dump",
            description =
                    "Cached on-demand fetcher for https://dumps.kodikres.com/calendar.json (5 min"
                            + " TTL, conditional GET). Filters are optional and combinable.")
    public Mono<ResponseEntity<CalendarResponse>> getCalendar(
            @Parameter(description = "Filter by Shikimori status (e.g. ongoing, anons, released)")
                    @RequestParam(required = false)
                    String status,
            @Parameter(description = "Filter by Shikimori kind (e.g. tv, movie, ova, ona)")
                    @RequestParam(required = false)
                    String kind,
            @Parameter(description = "Inclusive lower bound for Shikimori score")
                    @RequestParam(required = false)
                    Double minScore,
            @Parameter(description = "Cap returned entries (after filtering)")
                    @RequestParam(required = false)
                    Integer limit,
            @Parameter(description = "Match Shikimori ids to local kodik_content")
                    @RequestParam(defaultValue = "false")
                    boolean enrich) {
        CalendarFilter filter = new CalendarFilter(status, kind, minScore, limit);
        return Mono.fromCallable(() -> calendarService.get(filter, enrich))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/outbox")
    @Operation(
            summary = "Replay calendar deltas detected by the watcher (CAL-6)",
            description =
                    "Returns one entry per detected change (NEW_ANIME, NEXT_EPISODE_ADVANCED, "
                            + "STATUS_CHANGED, …) since the supplied watermark. Pull pattern: "
                            + "consumers pass back the last detectedAt they processed. Default "
                            + "watermark = 1970-01-01 (read everything). Capped at 1000 events.")
    public Mono<ResponseEntity<Map<String, Object>>> outbox(
            @Parameter(
                            description =
                                    "Exclusive lower bound on detectedAt (ISO-8601 local datetime;"
                                            + " default 1970-01-01T00:00:00)")
                    @RequestParam(required = false)
                    String since,
            @Parameter(description = "Cap returned events (1..1000)")
                    @RequestParam(required = false)
                    Integer limit) {
        LocalDateTime watermark =
                since == null || since.isBlank()
                        ? LocalDateTime.of(1970, 1, 1, 0, 0)
                        : LocalDateTime.parse(since);
        int cap =
                Math.max(
                        1,
                        Math.min(OUTBOX_LIMIT_MAX, limit == null ? OUTBOX_LIMIT_DEFAULT : limit));
        return Mono.fromCallable(() -> outboxRepository.findSince(watermark, cap))
                .subscribeOn(Schedulers.boundedElastic())
                .map(events -> ResponseEntity.ok(toBody(watermark, cap, events)));
    }

    private static Map<String, Object> toBody(
            LocalDateTime since, int limit, List<KodikCalendarOutboxEvent> events) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("since", since.toString());
        body.put("limit", limit);
        body.put("count", events.size());
        body.put(
                "nextSince",
                events.isEmpty()
                        ? since.toString()
                        : events.get(events.size() - 1).getDetectedAt().toString());
        body.put("events", events);
        return body;
    }
}
