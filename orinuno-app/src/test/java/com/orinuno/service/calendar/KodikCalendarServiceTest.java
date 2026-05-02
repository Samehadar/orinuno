package com.orinuno.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.orinuno.client.calendar.CalendarFetchResult;
import com.orinuno.client.calendar.KodikCalendarHttpClient;
import com.orinuno.client.dto.calendar.KodikCalendarAnimeDto;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.client.dto.calendar.KodikCalendarImageDto;
import com.orinuno.model.dto.CalendarResponse;
import com.orinuno.model.dto.CalendarResponse.EnrichedCalendarEntryDto;
import com.orinuno.repository.ContentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class KodikCalendarServiceTest {

    @Mock private KodikCalendarHttpClient httpClient;
    @Mock private ContentRepository contentRepository;

    private KodikCalendarService service;

    @BeforeEach
    void setUp() {
        service = new KodikCalendarService(httpClient, contentRepository);
    }

    @Test
    @DisplayName("Filter by status returns only matching entries")
    void filterByStatus() {
        when(httpClient.fetch()).thenReturn(Mono.just(sampleResult()));

        CalendarResponse response =
                service.get(new CalendarFilter("ongoing", null, null, null), false);

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.entries())
                .extracting(e -> e.entry().anime().id())
                .containsExactly("1", "3");
    }

    @Test
    @DisplayName("Filter combines kind + minScore")
    void filterCombinesKindAndScore() {
        when(httpClient.fetch()).thenReturn(Mono.just(sampleResult()));

        CalendarResponse response = service.get(new CalendarFilter(null, "tv", 8.0, null), false);

        assertThat(response.entries())
                .hasSize(2)
                .extracting(e -> e.entry().anime().id())
                .containsExactly("1", "3");
    }

    @Test
    @DisplayName("Filter limit caps the response and stops iteration")
    void filterLimitCaps() {
        when(httpClient.fetch()).thenReturn(Mono.just(sampleResult()));

        CalendarResponse response = service.get(new CalendarFilter(null, null, null, 2), false);

        assertThat(response.entries()).hasSize(2);
    }

    @Test
    @DisplayName("Empty filter returns the entire upstream payload")
    void filterNoneReturnsAll() {
        when(httpClient.fetch()).thenReturn(Mono.just(sampleResult()));

        CalendarResponse response = service.get(CalendarFilter.none(), false);

        assertThat(response.total()).isEqualTo(4);
    }

    @Test
    @DisplayName("Enrichment maps Shikimori ids to local content ids")
    void enrichmentMapsKnownIds() {
        when(httpClient.fetch()).thenReturn(Mono.just(sampleResult()));
        when(contentRepository.findIdsByShikimoriIds(anyList()))
                .thenReturn(
                        List.of(
                                Map.of("id", 101L, "shikimoriId", "1"),
                                Map.of("id", 303L, "shikimoriId", "3")));

        CalendarResponse response = service.get(CalendarFilter.none(), true);

        assertThat(response.entries())
                .extracting(EnrichedCalendarEntryDto::orinunoContentId)
                .containsExactly(101L, null, 303L, null);
    }

    @Test
    @DisplayName("Enrichment skipped when enrich=false leaves all ids null")
    void enrichmentDisabled() {
        when(httpClient.fetch()).thenReturn(Mono.just(sampleResult()));

        CalendarResponse response = service.get(CalendarFilter.none(), false);

        assertThat(response.entries())
                .extracting(EnrichedCalendarEntryDto::orinunoContentId)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("Force refresh delegates to httpClient.fetch and bypasses cache")
    void forceRefreshDelegates() {
        CalendarFetchResult expected = sampleResult();
        when(httpClient.fetch()).thenReturn(Mono.just(expected));

        CalendarFetchResult result = service.forceRefresh();

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("Static filter helper handles null filter and returns input list directly")
    void filterHelperNullPassThrough() {
        List<KodikCalendarEntryDto> entries = sampleResult().entries();
        assertThat(KodikCalendarService.applyFilter(entries, null)).isSameAs(entries);
    }

    private CalendarFetchResult sampleResult() {
        return new CalendarFetchResult(
                Instant.parse("2026-04-27T00:00:00Z"),
                "\"abc\"",
                "Mon, 27 Apr 2026 00:00:00 GMT",
                List.of(
                        entry("1", "tv", "ongoing", 9.0),
                        entry("2", "movie", "released", 7.0),
                        entry("3", "tv", "ongoing", 8.5),
                        entry("4", "ona", "anons", 6.5)));
    }

    private KodikCalendarEntryDto entry(String id, String kind, String status, double score) {
        return new KodikCalendarEntryDto(
                1,
                Instant.parse("2026-05-01T00:00:00Z"),
                24,
                new KodikCalendarAnimeDto(
                        id,
                        "anime-" + id,
                        "аниме-" + id,
                        new KodikCalendarImageDto(null, null, null, null, null)),
                kind,
                score,
                status,
                12,
                5,
                null,
                null);
    }
}
