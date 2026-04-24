package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.client.dto.KodikSearchResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikSearchResponseDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should deserialize full Kodik API response")
    void shouldDeserializeFullResponse() throws Exception {
        String json =
                """
                {
                    "time": "2ms",
                    "total": 1,
                    "results": [
                        {
                            "id": "movie-12345",
                            "type": "foreign-movie",
                            "link": "//kodik.info/video/12345/hash/720p",
                            "title": "Test Movie",
                            "title_orig": "Test Movie Original",
                            "other_title": "Another Title",
                            "translation": {
                                "id": 1,
                                "title": "Дублированный",
                                "type": "voice"
                            },
                            "year": 2024,
                            "kinopoisk_id": "1234567",
                            "imdb_id": "tt1234567",
                            "shikimori_id": "54321",
                            "mdl_id": "56743-mei-gui-qi-shi",
                            "worldart_link": "http://worldart.ru/animation/12345",
                            "worldart_animation_id": "10534",
                            "worldart_cinema_id": "7788",
                            "quality": "BDRip 720p",
                            "camrip": false,
                            "lgbt": false,
                            "screenshots": [
                                "https://i.kodik.biz/screenshots/1.jpg",
                                "https://i.kodik.biz/screenshots/2.jpg"
                            ],
                            "last_season": 0,
                            "last_episode": 0,
                            "episodes_count": 0,
                            "created_at": "2024-01-01T00:00:00Z",
                            "updated_at": "2024-06-01T00:00:00Z"
                        }
                    ]
                }
                """;

        KodikSearchResponse response = objectMapper.readValue(json, KodikSearchResponse.class);

        assertThat(response.getTotal()).isEqualTo(1);
        assertThat(response.getResults()).hasSize(1);

        KodikSearchResponse.Result result = response.getResults().get(0);
        assertThat(result.getId()).isEqualTo("movie-12345");
        assertThat(result.getType()).isEqualTo("foreign-movie");
        assertThat(result.getTitle()).isEqualTo("Test Movie");
        assertThat(result.getTitleOrig()).isEqualTo("Test Movie Original");
        assertThat(result.getKinopoiskId()).isEqualTo("1234567");
        assertThat(result.getImdbId()).isEqualTo("tt1234567");
        assertThat(result.getShikimoriId()).isEqualTo("54321");
        assertThat(result.getMdlId()).isEqualTo("56743-mei-gui-qi-shi");
        assertThat(result.getWorldartLink()).isEqualTo("http://worldart.ru/animation/12345");
        assertThat(result.getWorldartAnimationId()).isEqualTo("10534");
        assertThat(result.getWorldartCinemaId()).isEqualTo("7788");
        assertThat(result.getQuality()).isEqualTo("BDRip 720p");
        assertThat(result.getCamrip()).isFalse();
        assertThat(result.getScreenshots()).hasSize(2);
        assertThat(result.getTranslation().getId()).isEqualTo(1);
        assertThat(result.getTranslation().getTitle()).isEqualTo("Дублированный");
    }

    @Test
    @DisplayName("Should deserialize response with seasons and episodes")
    void shouldDeserializeWithSeasons() throws Exception {
        String json =
                """
                {
                    "time": "3ms",
                    "total": 1,
                    "results": [
                        {
                            "id": "serial-999",
                            "type": "foreign-serial",
                            "link": "//kodik.info/serial/999/hash",
                            "title": "Test Serial",
                            "translation": {
                                "id": 2,
                                "title": "Субтитры",
                                "type": "subtitles"
                            },
                            "year": 2023,
                            "last_season": 2,
                            "last_episode": 10,
                            "episodes_count": 20,
                            "seasons": {
                                "1": {
                                    "link": "//kodik.info/serial/999/hash/s1",
                                    "episodes": {
                                        "1": "//kodik.info/serial/999/hash/s1e1",
                                        "2": "//kodik.info/serial/999/hash/s1e2"
                                    }
                                },
                                "2": {
                                    "link": "//kodik.info/serial/999/hash/s2",
                                    "episodes": {
                                        "1": "//kodik.info/serial/999/hash/s2e1"
                                    }
                                }
                            }
                        }
                    ]
                }
                """;

        KodikSearchResponse response = objectMapper.readValue(json, KodikSearchResponse.class);
        KodikSearchResponse.Result result = response.getResults().get(0);

        assertThat(result.getSeasons()).hasSize(2);
        assertThat(result.getSeasons().get("1").getEpisodes()).hasSize(2);
        assertThat(result.getSeasons().get("2").getEpisodes()).hasSize(1);
        assertThat(result.getLastSeason()).isEqualTo(2);
        assertThat(result.getEpisodesCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("Should deserialize blocked_seasons, blocked_countries and material_data")
    void shouldDeserializeBlockingAndMaterialData() throws Exception {
        String json =
                """
                {
                    "time": "2ms",
                    "total": 1,
                    "results": [
                        {
                            "id": "serial-777",
                            "type": "anime-serial",
                            "link": "//kodik.info/serial/777/hash",
                            "title": "Test Anime",
                            "translation": {"id": 1, "title": "AniLibria", "type": "voice"},
                            "year": 2023,
                            "blocked_countries": ["RU", "BY"],
                            "blocked_seasons": {"1": {"RU": true}},
                            "material_data": {
                                "title": "Test Anime",
                                "kinopoisk_rating": 8.1,
                                "imdb_rating": 7.9,
                                "shikimori_rating": 8.5,
                                "all_genres": ["комедия", "приключения"],
                                "actors": ["Actor One", "Actor Two"],
                                "anime_status": "released",
                                "duration": 24
                            }
                        }
                    ]
                }
                """;

        KodikSearchResponse response = objectMapper.readValue(json, KodikSearchResponse.class);
        KodikSearchResponse.Result result = response.getResults().get(0);

        assertThat(result.getBlockedCountries()).containsExactly("RU", "BY");
        assertThat(result.getBlockedSeasons()).isNotNull();
        assertThat(result.getBlockedSeasons()).containsKey("1");
        assertThat(result.getMaterialData()).isNotNull();
        assertThat(result.getMaterialData().get("kinopoisk_rating")).isEqualTo(8.1);
        assertThat(result.getMaterialData().get("imdb_rating")).isEqualTo(7.9);
        assertThat(result.getMaterialData().get("shikimori_rating")).isEqualTo(8.5);
        assertThat(result.getMaterialData().get("all_genres")).isInstanceOf(java.util.List.class);
        assertThat(result.getMaterialData().get("anime_status")).isEqualTo("released");
    }

    @Test
    @DisplayName("Should handle null blocked_seasons and blocked_countries")
    void shouldHandleNullBlockingFields() throws Exception {
        String json =
                """
                {
                    "time": "1ms",
                    "total": 1,
                    "results": [
                        {
                            "id": "movie-555",
                            "type": "foreign-movie",
                            "link": "//kodik.info/video/555/hash",
                            "title": "No Block Movie",
                            "translation": {"id": 1, "title": "Test", "type": "voice"},
                            "year": 2024,
                            "blocked_countries": null,
                            "blocked_seasons": null,
                            "material_data": null
                        }
                    ]
                }
                """;

        KodikSearchResponse response = objectMapper.readValue(json, KodikSearchResponse.class);
        KodikSearchResponse.Result result = response.getResults().get(0);

        assertThat(result.getBlockedCountries()).isNull();
        assertThat(result.getBlockedSeasons()).isNull();
        assertThat(result.getMaterialData()).isNull();
    }

    @Test
    @DisplayName("Should deserialize material_data with anime-specific fields (anime_poster_url)")
    void shouldDeserializeMaterialDataWithAnimeFields() throws Exception {
        String json =
                """
{
    "time": "2ms",
    "total": 1,
    "results": [
        {
            "id": "serial-888",
            "type": "anime-serial",
            "link": "//kodik.info/serial/888/hash",
            "title": "Test Anime",
            "translation": {"id": 1, "title": "AniLibria", "type": "voice"},
            "year": 2024,
            "material_data": {
                "title": "Test Anime",
                "anime_title": "テストアニメ",
                "anime_poster_url": "https://shikimori.one/system/animes/x96/20.jpg",
                "poster_url": "https://st.kp.yandex.net/images/film_iphone/123.jpg",
                "anime_kind": "tv",
                "anime_status": "released",
                "anime_genres": ["Экшен", "Приключения"],
                "anime_studios": ["Pierrot"],
                "anime_description": "Anime description here",
                "shikimori_rating": 8.5,
                "shikimori_votes": 12345,
                "episodes_total": 220,
                "episodes_aired": 220,
                "tagline": "Believe it!"
            }
        }
    ]
}
""";

        KodikSearchResponse response = objectMapper.readValue(json, KodikSearchResponse.class);
        KodikSearchResponse.Result result = response.getResults().get(0);

        assertThat(result.getMaterialData()).isNotNull();
        assertThat(result.getMaterialData().get("anime_poster_url"))
                .isEqualTo("https://shikimori.one/system/animes/x96/20.jpg");
        assertThat(result.getMaterialData().get("anime_title")).isEqualTo("テストアニメ");
        assertThat(result.getMaterialData().get("anime_kind")).isEqualTo("tv");
        assertThat(result.getMaterialData().get("anime_status")).isEqualTo("released");
        assertThat(result.getMaterialData().get("anime_genres")).isInstanceOf(java.util.List.class);
        assertThat(result.getMaterialData().get("anime_studios"))
                .isInstanceOf(java.util.List.class);
        assertThat(result.getMaterialData().get("tagline")).isEqualTo("Believe it!");
    }

    @Test
    @DisplayName("Should deserialize material_data with drama-specific fields")
    void shouldDeserializeMaterialDataWithDramaFields() throws Exception {
        String json =
                """
                {
                    "time": "2ms",
                    "total": 1,
                    "results": [
                        {
                            "id": "serial-999",
                            "type": "foreign-serial",
                            "link": "//kodik.info/serial/999/hash",
                            "title": "Squid Game",
                            "translation": {"id": 5, "title": "HDRezka", "type": "voice"},
                            "year": 2021,
                            "material_data": {
                                "title": "Игра в кальмара",
                                "title_en": "Squid Game",
                                "drama_status": "released",
                                "drama_poster_url": "https://mydramalist.com/posters/squid.jpg",
                                "drama_genres": ["Триллер", "Драма", "Выживание"],
                                "mydramalist_tags": ["Dark", "Survival", "Social Commentary"],
                                "mydramalist_rating": 8.6,
                                "mydramalist_votes": 54321,
                                "kinopoisk_rating": 7.9,
                                "imdb_rating": 8.0,
                                "all_genres": ["триллер", "драма"],
                                "all_status": "released",
                                "poster_url": "https://st.kp.yandex.net/images/film/456.jpg"
                            }
                        }
                    ]
                }
                """;

        KodikSearchResponse response = objectMapper.readValue(json, KodikSearchResponse.class);
        KodikSearchResponse.Result result = response.getResults().get(0);

        assertThat(result.getMaterialData()).isNotNull();
        assertThat(result.getMaterialData().get("drama_status")).isEqualTo("released");
        assertThat(result.getMaterialData().get("drama_poster_url"))
                .isEqualTo("https://mydramalist.com/posters/squid.jpg");
        assertThat(result.getMaterialData().get("drama_genres")).isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<String> dramaGenres =
                (java.util.List<String>) result.getMaterialData().get("drama_genres");
        assertThat(dramaGenres).containsExactly("Триллер", "Драма", "Выживание");
        assertThat(result.getMaterialData().get("mydramalist_tags"))
                .isInstanceOf(java.util.List.class);
        @SuppressWarnings("unchecked")
        java.util.List<String> mdlTags =
                (java.util.List<String>) result.getMaterialData().get("mydramalist_tags");
        assertThat(mdlTags).contains("Dark", "Survival");
        assertThat(result.getMaterialData().get("mydramalist_rating")).isEqualTo(8.6);
    }

    @Test
    @DisplayName("Should handle unknown fields gracefully")
    void shouldIgnoreUnknownFields() throws Exception {
        String json =
                """
                {
                    "time": "1ms",
                    "total": 0,
                    "results": [],
                    "some_unknown_field": "value",
                    "another_field": 42
                }
                """;

        KodikSearchResponse response = objectMapper.readValue(json, KodikSearchResponse.class);
        assertThat(response.getTotal()).isEqualTo(0);
        assertThat(response.getResults()).isEmpty();
    }
}
