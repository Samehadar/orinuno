package com.orinuno.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.drift.DtoFieldExtractor;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KodikMaterialDataDto — known field matrix")
class KodikMaterialDataDtoTest {

    @Test
    @DisplayName("DtoFieldExtractor exposes every documented material_data field")
    void exposesAllDocumentedFields() {
        Set<String> fields = DtoFieldExtractor.knownJsonFields(KodikMaterialDataDto.class);

        assertThat(fields)
                .contains(
                        "title",
                        "anime_title",
                        "title_en",
                        "other_titles",
                        "other_titles_en",
                        "other_titles_jp",
                        "anime_license_name",
                        "anime_licensed_by",
                        "anime_kind",
                        "all_status",
                        "anime_status",
                        "drama_status",
                        "year",
                        "description",
                        "poster_url",
                        "screenshots",
                        "duration",
                        "countries",
                        "all_genres",
                        "genres",
                        "anime_genres",
                        "drama_genres",
                        "anime_studios",
                        "rating",
                        "kinopoisk_rating",
                        "kinopoisk_votes",
                        "imdb_rating",
                        "imdb_votes",
                        "shikimori_rating",
                        "shikimori_votes",
                        "mydramalist_rating",
                        "mydramalist_votes",
                        "premiere_world",
                        "premiere_ru",
                        "premiere_country",
                        "aired_at",
                        "released_at",
                        "next_episode_at",
                        "episodes_total",
                        "episodes_aired",
                        "actors",
                        "directors",
                        "producers",
                        "writers",
                        "composers",
                        "editors",
                        "designers",
                        "operators",
                        "rating_mpaa",
                        "minimal_age",
                        "anime_description",
                        "poster_url_original",
                        "mydramalist_tags",
                        "blocked_countries",
                        "blocked_seasons",
                        "anime_poster_url",
                        "drama_poster_url",
                        "tagline");
    }

    @Test
    @DisplayName("record stays cached across invocations")
    void cached() {
        Set<String> first = DtoFieldExtractor.knownJsonFields(KodikMaterialDataDto.class);
        Set<String> second = DtoFieldExtractor.knownJsonFields(KodikMaterialDataDto.class);
        assertThat(first).isSameAs(second);
    }
}
