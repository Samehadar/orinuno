package com.orinuno.controller;

import com.orinuno.model.dto.CalendarResponse;
import com.orinuno.service.calendar.CalendarFilter;
import com.orinuno.service.calendar.KodikCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    private final KodikCalendarService calendarService;

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
}
