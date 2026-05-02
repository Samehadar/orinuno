package com.orinuno.client.dto.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikCalendarEntryDtoTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Test
    @DisplayName("Realistic calendar entry parses with snake_case to camelCase mapping")
    void parsesRealisticEntry() throws Exception {
        String json =
                """
                {
                  "next_episode": 26,
                  "next_episode_at": "2026-04-29T16:30:00+03:00",
                  "duration": 24,
                  "anime": {
                    "id": "60022",
                    "name": "Solo Leveling 2",
                    "russian": "Поднятие уровня в одиночку 2",
                    "image": {
                      "original": "/system/animes/original/60022.jpg",
                      "preview": "/system/animes/preview/60022.jpg",
                      "x96": "/system/animes/x96/60022.jpg",
                      "x48": "/system/animes/x48/60022.jpg",
                      "x24": "/system/animes/x24/60022.jpg"
                    }
                  },
                  "kind": "tv",
                  "score": 8.45,
                  "status": "ongoing",
                  "episodes": 26,
                  "episodes_aired": 25,
                  "aired_on": "2026-01-04",
                  "released_on": null
                }
                """;

        KodikCalendarEntryDto entry = mapper.readValue(json, KodikCalendarEntryDto.class);

        assertThat(entry.nextEpisode()).isEqualTo(26);
        assertThat(entry.nextEpisodeAt()).isEqualTo(Instant.parse("2026-04-29T13:30:00Z"));
        assertThat(entry.duration()).isEqualTo(24);
        assertThat(entry.kind()).isEqualTo("tv");
        assertThat(entry.score()).isEqualTo(8.45);
        assertThat(entry.status()).isEqualTo("ongoing");
        assertThat(entry.episodes()).isEqualTo(26);
        assertThat(entry.episodesAired()).isEqualTo(25);
        assertThat(entry.airedOn()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(entry.releasedOn()).isNull();
        assertThat(entry.anime().id()).isEqualTo("60022");
        assertThat(entry.anime().russian()).isEqualTo("Поднятие уровня в одиночку 2");
        assertThat(entry.anime().image().x96()).isEqualTo("/system/animes/x96/60022.jpg");
    }

    @Test
    @DisplayName("Unknown upstream fields are silently ignored")
    void ignoresUnknownFields() throws Exception {
        String json =
                """
                {
                  "next_episode": 1,
                  "anime": {"id": "1", "name": "x", "russian": "x", "image": {}},
                  "kind": "tv",
                  "status": "anons",
                  "future_field_2027": "surprise!",
                  "experimental_block": {"deeply": {"nested": true}}
                }
                """;

        KodikCalendarEntryDto entry = mapper.readValue(json, KodikCalendarEntryDto.class);

        assertThat(entry.nextEpisode()).isEqualTo(1);
        assertThat(entry.kind()).isEqualTo("tv");
        assertThat(entry.status()).isEqualTo("anons");
    }

    @Test
    @DisplayName("List of entries deserializes when upstream returns array root")
    void parsesArray() throws Exception {
        String json =
                """
[
  {"next_episode": 1, "anime": {"id": "1", "name": "a", "russian": "a", "image": {}},
   "kind": "tv", "status": "ongoing"},
  {"next_episode": 7, "anime": {"id": "2", "name": "b", "russian": "b", "image": {}},
   "kind": "movie", "status": "released"}
]
""";

        List<KodikCalendarEntryDto> list =
                mapper.readValue(
                        json,
                        mapper.getTypeFactory()
                                .constructCollectionType(List.class, KodikCalendarEntryDto.class));

        assertThat(list).hasSize(2);
        assertThat(list.get(0).status()).isEqualTo("ongoing");
        assertThat(list.get(1).kind()).isEqualTo("movie");
    }
}
