package com.orinuno.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.dto.calendar.KodikCalendarAnimeDto;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.client.dto.calendar.KodikCalendarImageDto;
import com.orinuno.model.dto.CalendarResponse;
import com.orinuno.model.dto.CalendarResponse.EnrichedCalendarEntryDto;
import com.orinuno.service.calendar.CalendarFilter;
import com.orinuno.service.calendar.KodikCalendarService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

@ExtendWith(MockitoExtension.class)
class KodikCalendarControllerTest {

    @Mock private KodikCalendarService service;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        KodikCalendarController controller = new KodikCalendarController(service);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/calendar passes filters and enrich flag to service")
    void delegatesParameters() {
        CalendarResponse stub = stubResponse();
        when(service.get(any(CalendarFilter.class), anyBoolean())).thenReturn(stub);

        client.get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/v1/calendar")
                                        .queryParam("status", "ongoing")
                                        .queryParam("kind", "tv")
                                        .queryParam("minScore", "8.0")
                                        .queryParam("limit", "5")
                                        .queryParam("enrich", "true")
                                        .build())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.total")
                .isEqualTo(1)
                .jsonPath("$.entries[0].entry.anime.id")
                .isEqualTo("10")
                .jsonPath("$.entries[0].orinunoContentId")
                .isEqualTo(99);

        ArgumentCaptor<CalendarFilter> filterCaptor = ArgumentCaptor.forClass(CalendarFilter.class);
        ArgumentCaptor<Boolean> enrichCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(service).get(filterCaptor.capture(), enrichCaptor.capture());

        CalendarFilter filter = filterCaptor.getValue();
        assertThat(filter.status()).isEqualTo("ongoing");
        assertThat(filter.kind()).isEqualTo("tv");
        assertThat(filter.minScore()).isEqualTo(8.0);
        assertThat(filter.limit()).isEqualTo(5);
        assertThat(enrichCaptor.getValue()).isTrue();
    }

    @Test
    @DisplayName("GET without query params defaults to empty filter and enrich=false")
    void defaultsApplied() {
        CalendarResponse stub = stubResponse();
        when(service.get(any(CalendarFilter.class), anyBoolean())).thenReturn(stub);

        client.get().uri("/api/v1/calendar").exchange().expectStatus().isOk();

        ArgumentCaptor<CalendarFilter> filterCaptor = ArgumentCaptor.forClass(CalendarFilter.class);
        ArgumentCaptor<Boolean> enrichCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(service).get(filterCaptor.capture(), enrichCaptor.capture());
        assertThat(filterCaptor.getValue().status()).isNull();
        assertThat(filterCaptor.getValue().kind()).isNull();
        assertThat(filterCaptor.getValue().minScore()).isNull();
        assertThat(filterCaptor.getValue().limit()).isNull();
        assertThat(enrichCaptor.getValue()).isFalse();
    }

    private CalendarResponse stubResponse() {
        KodikCalendarEntryDto entry =
                new KodikCalendarEntryDto(
                        1,
                        Instant.parse("2026-05-01T00:00:00Z"),
                        24,
                        new KodikCalendarAnimeDto(
                                "10",
                                "x",
                                "икс",
                                new KodikCalendarImageDto(null, null, null, null, null)),
                        "tv",
                        9.0,
                        "ongoing",
                        12,
                        3,
                        null,
                        null);
        return new CalendarResponse(
                Instant.parse("2026-04-27T00:00:00Z"),
                "\"abc\"",
                1,
                List.of(new EnrichedCalendarEntryDto(entry, 99L)));
    }
}
