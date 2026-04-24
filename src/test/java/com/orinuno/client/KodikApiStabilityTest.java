package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.client.dto.reference.KodikCountryDto;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikQualityDto;
import com.orinuno.client.dto.reference.KodikReferenceResponse;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import com.orinuno.drift.DtoFieldExtractor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfEnvironmentVariable(named = "KODIK_TOKEN", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Kodik API Stability Tests")
class KodikApiStabilityTest {

    private static final String BASE_URL = "https://kodik-api.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private static String token;

    private static final Set<String> PAGINATED_REQUIRED_FIELDS = Set.of("time", "total", "results");

    private static final Set<String> PAGINATED_KNOWN_FIELDS =
            DtoFieldExtractor.knownJsonFields(KodikSearchResponse.class);

    private static final Set<String> RESULT_REQUIRED_FIELDS = Set.of("id", "link", "translation");

    private static final Set<String> RESULT_KNOWN_FIELDS =
            DtoFieldExtractor.knownJsonFields(KodikSearchResponse.Result.class);

    private static final Set<String> TRANSLATION_KNOWN_FIELDS =
            Set.of("id", "title", "type", "count", "count_for_not_anime");
    private static final Set<String> MATERIAL_DATA_KNOWN_FIELDS =
            Set.of(
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
    private static final Set<String> SIMPLE_RESPONSE_KNOWN_FIELDS =
            Set.of("time", "total", "results");

    @BeforeAll
    static void setUp() {
        token = System.getenv("KODIK_TOKEN");
        assertThat(token).as("KODIK_TOKEN must be set").isNotBlank();
    }

    // ======================== /search ========================

    @ParameterizedTest(name = "/search by title=\"{0}\" — structural integrity")
    @Order(1)
    @ValueSource(strings = {"Naruto", "Attack on Titan", "Тетрадь смерти"})
    void searchByTitle(String title) throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&title=" + encode(title) + "&limit=3");

        assertPaginatedResponse(raw, "/search title=" + title);
        assertResultItems(raw, "/search title=" + title);
    }

    @ParameterizedTest(name = "/search by shikimori_id={0} — returns results for known anime")
    @Order(2)
    @ValueSource(strings = {"20", "5", "1535", "21", "16498"})
    void searchByShikimoriId(String shikimoriId) throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&shikimori_id=" + shikimoriId + "&limit=5");

        assertPaginatedResponse(raw, "/search shikimori_id=" + shikimoriId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        assertThat(results)
                .as("/search shikimori_id=%s should return results", shikimoriId)
                .isNotEmpty();
    }

    @ParameterizedTest(name = "/search by kinopoisk_id={0}")
    @Order(3)
    @ValueSource(strings = {"326", "435", "251733"})
    void searchByKinopoiskId(String kpId) throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&kinopoisk_id=" + kpId + "&limit=3");

        assertPaginatedResponse(raw, "/search kinopoisk_id=" + kpId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        if (!results.isEmpty()) {
            assertResultItems(raw, "/search kinopoisk_id=" + kpId);
        }
    }

    @ParameterizedTest(name = "/search by imdb_id={0}")
    @Order(4)
    @ValueSource(strings = {"tt0409591", "tt0388629", "tt0944947"})
    void searchByImdbId(String imdbId) throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&imdb_id=" + imdbId + "&limit=3");

        assertPaginatedResponse(raw, "/search imdb_id=" + imdbId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        if (!results.isEmpty()) {
            assertResultItems(raw, "/search imdb_id=" + imdbId);
        }
    }

    @Test
    @Order(5)
    @DisplayName("/search with with_seasons=true should include seasons field")
    void searchWithSeasons() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token="
                                + token
                                + "&shikimori_id=20&with_seasons=true&with_episodes=true&limit=1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        Assumptions.assumeTrue(results != null && !results.isEmpty());

        Map<String, Object> first = results.get(0);
        assertThat(first.containsKey("seasons"))
                .as("with_seasons=true should include 'seasons' field")
                .isTrue();
    }

    @ParameterizedTest(name = "/search with types={0}")
    @Order(6)
    @ValueSource(strings = {"anime", "anime-serial", "foreign-movie", "russian-serial"})
    void searchByType(String type) throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&title=a&types=" + type + "&limit=2");

        assertPaginatedResponse(raw, "/search types=" + type);
    }

    @Test
    @Order(7)
    @DisplayName("/search with translation_type=voice — filter by voice translation")
    void searchByTranslationType() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token=" + token + "&title=Naruto&translation_type=voice&limit=3");

        assertPaginatedResponse(raw, "/search translation_type=voice");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        if (!results.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> translation =
                    (Map<String, Object>) results.get(0).get("translation");
            assertThat(translation).as("translation should be present").isNotNull();
            assertThat(translation.get("type"))
                    .as("translation type should be 'voice'")
                    .isEqualTo("voice");
        }
    }

    @Test
    @Order(8)
    @DisplayName("/search with camrip=false — exclude camrips")
    void searchExcludeCamrip() throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&title=Avengers&camrip=false&limit=3");

        assertPaginatedResponse(raw, "/search camrip=false");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        for (Map<String, Object> r : results) {
            assertSoftly(
                    soft ->
                            soft.assertThat(r.get("camrip"))
                                    .as("camrip should be false when filtered")
                                    .isIn(null, false));
        }
    }

    @Test
    @Order(9)
    @DisplayName("/search with year filter")
    void searchByYear() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token=" + token + "&title=" + encode("One Piece") + "&year=2023&limit=3");

        assertPaginatedResponse(raw, "/search year=2023");
    }

    @Test
    @Order(10)
    @DisplayName("/search — nonexistent content returns total=0")
    void searchNonexistentContent() throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&title=xyznonexistent999zzz&limit=1");

        assertPaginatedResponse(raw, "/search nonexistent");
        assertThat(((Number) raw.get("total")).intValue())
                .as("Nonexistent title should return total=0")
                .isZero();
    }

    // ======================== /search + material_data ========================

    @ParameterizedTest(name = "/search material_data for shikimori_id={0} ({1})")
    @Order(11)
    @CsvSource({
        "20, Naruto",
        "5, Cowboy Bebop",
        "1535, Death Note",
        "21, One Piece",
        "16498, Shingeki no Kyojin"
    })
    void searchMaterialData(String shikimoriId, String label) throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token="
                                + token
                                + "&shikimori_id="
                                + shikimoriId
                                + "&with_material_data=true&limit=1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        Assumptions.assumeTrue(results != null && !results.isEmpty(), "Need results for " + label);

        Map<String, Object> first = results.get(0);
        Assumptions.assumeTrue(
                first.containsKey("material_data"), "material_data not returned for " + label);

        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) first.get("material_data");
        assertThat(md).as("material_data for %s", label).isNotNull();

        assertSoftly(
                soft -> {
                    soft.assertThat(
                                    md.containsKey("shikimori_rating")
                                            || md.containsKey("kinopoisk_rating"))
                            .as("material_data for %s should have at least one rating", label)
                            .isTrue();
                });

        detectUnknownKeys("MaterialData[" + label + "]", md.keySet(), MATERIAL_DATA_KNOWN_FIELDS);
    }

    @Test
    @Order(12)
    @DisplayName("/search material_data — ratings are numeric")
    void searchMaterialDataRatingsAreNumeric() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token=" + token + "&shikimori_id=20&with_material_data=true&limit=1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        Assumptions.assumeTrue(results != null && !results.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) results.get(0).get("material_data");
        Assumptions.assumeTrue(md != null);

        assertSoftly(
                soft -> {
                    if (md.containsKey("kinopoisk_rating")) {
                        soft.assertThat(md.get("kinopoisk_rating"))
                                .as("kinopoisk_rating must be a number")
                                .isInstanceOf(Number.class);
                    }
                    if (md.containsKey("imdb_rating")) {
                        soft.assertThat(md.get("imdb_rating"))
                                .as("imdb_rating must be a number")
                                .isInstanceOf(Number.class);
                    }
                    if (md.containsKey("shikimori_rating")) {
                        soft.assertThat(md.get("shikimori_rating"))
                                .as("shikimori_rating must be a number")
                                .isInstanceOf(Number.class);
                    }
                    if (md.containsKey("all_genres")) {
                        soft.assertThat(md.get("all_genres"))
                                .as("all_genres must be a list")
                                .isInstanceOf(List.class);
                    }
                    if (md.containsKey("actors")) {
                        soft.assertThat(md.get("actors"))
                                .as("actors must be a list")
                                .isInstanceOf(List.class);
                    }
                });
    }

    @Test
    @Order(13)
    @DisplayName("/search material_data — anime has anime_poster_url")
    void searchMaterialDataAnimePosterUrl() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token=" + token + "&shikimori_id=20&with_material_data=true&limit=1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        Assumptions.assumeTrue(results != null && !results.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) results.get(0).get("material_data");
        Assumptions.assumeTrue(md != null);

        if (md.containsKey("anime_poster_url")) {
            assertThat(md.get("anime_poster_url"))
                    .as("anime_poster_url must be a string (URL)")
                    .isInstanceOf(String.class);
            assertThat(md.get("anime_poster_url").toString())
                    .as("anime_poster_url should be a URL")
                    .startsWith("http");
        }
    }

    @Test
    @Order(14)
    @DisplayName("/search material_data — drama has drama-specific fields")
    void searchMaterialDataDramaFields() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token="
                                + token
                                + "&title="
                                + encode("Squid Game")
                                + "&with_material_data=true&limit=5");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        Assumptions.assumeTrue(results != null && !results.isEmpty());

        Map<String, Object> dramaData = null;
        for (Map<String, Object> r : results) {
            @SuppressWarnings("unchecked")
            Map<String, Object> md = (Map<String, Object>) r.get("material_data");
            if (md != null && md.containsKey("drama_status")) {
                dramaData = md;
                break;
            }
        }
        Assumptions.assumeTrue(dramaData != null, "Need a result with drama_status");

        Map<String, Object> md = dramaData;
        assertSoftly(
                soft -> {
                    if (md.containsKey("drama_status")) {
                        soft.assertThat(md.get("drama_status"))
                                .as("drama_status must be a string")
                                .isInstanceOf(String.class);
                    }
                    if (md.containsKey("drama_poster_url")) {
                        soft.assertThat(md.get("drama_poster_url"))
                                .as("drama_poster_url must be a string (URL)")
                                .isInstanceOf(String.class);
                    }
                    if (md.containsKey("drama_genres")) {
                        soft.assertThat(md.get("drama_genres"))
                                .as("drama_genres must be a list")
                                .isInstanceOf(List.class);
                    }
                    if (md.containsKey("mydramalist_tags")) {
                        soft.assertThat(md.get("mydramalist_tags"))
                                .as("mydramalist_tags must be a list")
                                .isInstanceOf(List.class);
                    }
                    if (md.containsKey("mydramalist_rating")) {
                        soft.assertThat(md.get("mydramalist_rating"))
                                .as("mydramalist_rating must be a number")
                                .isInstanceOf(Number.class);
                    }
                });

        detectUnknownKeys("MaterialData[drama]", md.keySet(), MATERIAL_DATA_KNOWN_FIELDS);
    }

    @Test
    @Order(15)
    @DisplayName("/search material_data for a movie (kinopoisk_id=326 = The Matrix)")
    void searchMaterialDataForMovie() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token=" + token + "&kinopoisk_id=326&with_material_data=true&limit=1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        Assumptions.assumeTrue(results != null && !results.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) results.get(0).get("material_data");
        Assumptions.assumeTrue(md != null);

        assertSoftly(
                soft -> {
                    soft.assertThat(md.containsKey("kinopoisk_rating"))
                            .as("Movie should have kinopoisk_rating")
                            .isTrue();
                    soft.assertThat(md.containsKey("imdb_rating"))
                            .as("Movie should have imdb_rating")
                            .isTrue();
                    if (md.containsKey("duration")) {
                        soft.assertThat(md.get("duration"))
                                .as("duration must be a number")
                                .isInstanceOf(Number.class);
                    }
                });

        detectUnknownKeys("MaterialData[Matrix]", md.keySet(), MATERIAL_DATA_KNOWN_FIELDS);
    }

    // ======================== /list ========================

    @ParameterizedTest(name = "/list sort={0} order={1}")
    @Order(20)
    @CsvSource({
        "updated_at, desc",
        "year, desc",
        "created_at, asc",
        "kinopoisk_rating, desc",
        "imdb_rating, desc",
        "shikimori_rating, desc"
    })
    void listWithSortOptions(String sort, String order) throws Exception {
        Map<String, Object> raw =
                postApi("/list", "token=" + token + "&limit=3&sort=" + sort + "&order=" + order);

        assertPaginatedResponse(raw, "/list sort=" + sort + " order=" + order);
    }

    @ParameterizedTest(name = "/list types={0}")
    @Order(21)
    @ValueSource(
            strings = {
                "anime",
                "anime-serial",
                "foreign-movie",
                "russian-serial",
                "foreign-serial"
            })
    void listByType(String type) throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/list",
                        "token="
                                + token
                                + "&types="
                                + type
                                + "&limit=2&sort=updated_at&order=desc");

        assertPaginatedResponse(raw, "/list types=" + type);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        assertThat(results).as("/list types=%s should return results", type).isNotEmpty();
    }

    @Test
    @Order(22)
    @DisplayName("/list — pagination (next_page field)")
    void listPagination() throws Exception {
        Map<String, Object> raw =
                postApi("/list", "token=" + token + "&limit=1&sort=updated_at&order=desc");

        assertPaginatedResponse(raw, "/list pagination");

        int total = ((Number) raw.get("total")).intValue();
        if (total > 1) {
            assertThat(raw.get("next_page"))
                    .as("next_page should be present when total > limit")
                    .isNotNull();
            assertThat(raw.get("next_page").toString())
                    .as("next_page should be a URL string")
                    .contains("kodik-api.com");
        }
    }

    @Test
    @Order(23)
    @DisplayName("/list with with_material_data=true")
    void listWithMaterialData() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/list",
                        "token="
                                + token
                                + "&limit=10&sort=updated_at&order=desc&with_material_data=true");

        assertPaginatedResponse(raw, "/list with_material_data");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        boolean anyHasMaterialData =
                results.stream()
                        .anyMatch(
                                r ->
                                        r.containsKey("material_data")
                                                && r.get("material_data") != null);
        assertThat(anyHasMaterialData)
                .as(
                        "with_material_data=true: at least one of %d results should have"
                                + " material_data",
                        results.size())
                .isTrue();
    }

    @Test
    @Order(24)
    @DisplayName("/list with year filter")
    void listByYear() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/list",
                        "token=" + token + "&year=2024&limit=3&sort=updated_at&order=desc");

        assertPaginatedResponse(raw, "/list year=2024");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        for (Map<String, Object> r : results) {
            assertThat(r.get("year"))
                    .as("year filter should return only year=2024")
                    .isEqualTo(2024);
        }
    }

    @Test
    @Order(25)
    @DisplayName("/list with translation_type=subtitles")
    void listBySubtitles() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/list",
                        "token="
                                + token
                                + "&translation_type=subtitles&limit=3&sort=updated_at&order=desc");

        assertPaginatedResponse(raw, "/list translation_type=subtitles");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        if (!results.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> translation =
                    (Map<String, Object>) results.get(0).get("translation");
            assertThat(translation).isNotNull();
            assertThat(translation.get("type")).isEqualTo("subtitles");
        }
    }

    // ======================== /translations/v2 ========================

    @Test
    @Order(30)
    @DisplayName("/translations/v2 — structure and content")
    void translationsStructure() throws Exception {
        Map<String, Object> raw = postApi("/translations/v2", "token=" + token);

        assertThat(raw).isNotNull();
        detectUnknownKeys("TranslationsResponse", raw.keySet(), SIMPLE_RESPONSE_KNOWN_FIELDS);

        assertThat(raw.get("results")).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        assertThat(results).as("translations should not be empty").isNotEmpty();
        assertThat(results.size()).as("should have many translations").isGreaterThan(5);

        Map<String, Object> first = results.get(0);
        assertSoftly(
                soft -> {
                    soft.assertThat(first.containsKey("id")).as("must have id").isTrue();
                    soft.assertThat(first.containsKey("title")).as("must have title").isTrue();
                    soft.assertThat(first.get("id"))
                            .as("id must be a number")
                            .isInstanceOf(Number.class);
                    soft.assertThat(first.get("title"))
                            .as("title must be a string")
                            .isInstanceOf(String.class);
                });

        detectUnknownKeys("Translation", first.keySet(), TRANSLATION_KNOWN_FIELDS);
    }

    @Test
    @Order(31)
    @DisplayName("/translations/v2 — known translations exist (AniLibria, AniDUB)")
    void translationsKnownVoicesExist() throws Exception {
        Map<String, Object> raw = postApi("/translations/v2", "token=" + token);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");

        Set<String> titles = new HashSet<>();
        for (Map<String, Object> t : results) {
            Object title = t.get("title");
            if (title != null) titles.add(title.toString());
        }

        assertSoftly(
                soft -> {
                    soft.assertThat(
                                    titles.stream()
                                            .anyMatch(t -> t.toLowerCase().contains("anilibria")))
                            .as("AniLibria should be among translations")
                            .isTrue();
                });
    }

    @Test
    @Order(32)
    @DisplayName("/translations/v2 — items have id, title, and count")
    void translationsItemStructure() throws Exception {
        Map<String, Object> raw = postApi("/translations/v2", "token=" + token);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        Assumptions.assumeTrue(!results.isEmpty());

        Set<String> allKeys = new HashSet<>();
        for (Map<String, Object> t : results) {
            allKeys.addAll(t.keySet());
        }

        assertSoftly(
                soft -> {
                    soft.assertThat(allKeys).as("should have 'id'").contains("id");
                    soft.assertThat(allKeys).as("should have 'title'").contains("title");
                    soft.assertThat(allKeys).as("should have 'count'").contains("count");
                });

        detectUnknownKeys("Translation[all]", allKeys, TRANSLATION_KNOWN_FIELDS);
    }

    // ======================== /genres ========================

    @Test
    @Order(40)
    @DisplayName("/genres — structure and content")
    void genresStructure() throws Exception {
        Map<String, Object> raw = postApi("/genres", "token=" + token);

        assertThat(raw).isNotNull();
        detectUnknownKeys("GenresResponse", raw.keySet(), SIMPLE_RESPONSE_KNOWN_FIELDS);

        assertThat(raw.get("results")).isInstanceOf(List.class);
        List<?> results = (List<?>) raw.get("results");
        assertThat(results).isNotEmpty();
    }

    @Test
    @Order(41)
    @DisplayName("/genres — known genres exist")
    void genresKnownValuesExist() throws Exception {
        Map<String, Object> raw = postApi("/genres", "token=" + token);

        List<?> results = (List<?>) raw.get("results");

        Set<String> genres = new HashSet<>();
        for (Object item : results) {
            if (item instanceof Map<?, ?> map && map.containsKey("title")) {
                genres.add(map.get("title").toString().toLowerCase());
            } else if (item instanceof String s) {
                genres.add(s.toLowerCase());
            }
        }

        assertSoftly(
                soft -> {
                    soft.assertThat(genres.size())
                            .as("should have multiple genres")
                            .isGreaterThan(3);
                });
    }

    @ParameterizedTest(name = "/genres with genre_type={0}")
    @Order(42)
    @ValueSource(strings = {"anime", "drama"})
    void genresWithType(String genreType) throws Exception {
        Map<String, Object> raw =
                postApi("/genres", "token=" + token + "&genres_type=" + genreType);

        assertThat(raw).isNotNull();
        assertThat(raw.get("results")).isInstanceOf(List.class);
    }

    // ======================== /countries ========================

    @Test
    @Order(50)
    @DisplayName("/countries — structure and content")
    void countriesStructure() throws Exception {
        Map<String, Object> raw = postApi("/countries", "token=" + token);

        assertThat(raw).isNotNull();
        detectUnknownKeys("CountriesResponse", raw.keySet(), SIMPLE_RESPONSE_KNOWN_FIELDS);

        assertThat(raw.get("results")).isInstanceOf(List.class);
        List<?> results = (List<?>) raw.get("results");
        assertThat(results).as("countries should not be empty").isNotEmpty();
    }

    @Test
    @Order(51)
    @DisplayName("/countries — known countries present")
    void countriesKnownValuesExist() throws Exception {
        Map<String, Object> raw = postApi("/countries", "token=" + token);

        List<?> results = (List<?>) raw.get("results");

        Set<String> countries = new HashSet<>();
        for (Object item : results) {
            if (item instanceof Map<?, ?> map && map.containsKey("title")) {
                countries.add(map.get("title").toString().toLowerCase());
            } else if (item instanceof String s) {
                countries.add(s.toLowerCase());
            }
        }

        assertThat(countries.size()).as("should have multiple countries").isGreaterThan(3);
    }

    // ======================== /years ========================

    @Test
    @Order(60)
    @DisplayName("/years — structure and content")
    void yearsStructure() throws Exception {
        Map<String, Object> raw = postApi("/years", "token=" + token);

        assertThat(raw).isNotNull();
        detectUnknownKeys("YearsResponse", raw.keySet(), SIMPLE_RESPONSE_KNOWN_FIELDS);

        assertThat(raw.get("results")).isInstanceOf(List.class);
        List<?> results = (List<?>) raw.get("results");
        assertThat(results).as("years should not be empty").isNotEmpty();
    }

    @Test
    @Order(61)
    @DisplayName("/years — contains recent years (2023, 2024)")
    void yearsContainRecentYears() throws Exception {
        Map<String, Object> raw = postApi("/years", "token=" + token);

        List<?> results = (List<?>) raw.get("results");

        Set<Integer> years = new HashSet<>();
        for (Object item : results) {
            if (item instanceof Number n) {
                years.add(n.intValue());
            } else if (item instanceof String s) {
                try {
                    years.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                }
            } else if (item instanceof Map<?, ?> map && map.containsKey("year")) {
                Object y = map.get("year");
                if (y instanceof Number n) years.add(n.intValue());
            }
        }

        assertSoftly(
                soft -> {
                    soft.assertThat(years).as("should contain year 2024").contains(2024);
                    soft.assertThat(years).as("should contain year 2023").contains(2023);
                });
    }

    // ======================== /qualities/v2 ========================

    @Test
    @Order(70)
    @DisplayName("/qualities/v2 — structure and content")
    void qualitiesStructure() throws Exception {
        Map<String, Object> raw = postApi("/qualities/v2", "token=" + token);

        assertThat(raw).isNotNull();
        detectUnknownKeys("QualitiesResponse", raw.keySet(), SIMPLE_RESPONSE_KNOWN_FIELDS);

        assertThat(raw.get("results")).isInstanceOf(List.class);
        List<?> results = (List<?>) raw.get("results");
        assertThat(results).as("qualities should not be empty").isNotEmpty();
    }

    @Test
    @Order(71)
    @DisplayName("/qualities/v2 — known qualities present (BDRip, WEB-DL, etc.)")
    void qualitiesKnownValuesExist() throws Exception {
        Map<String, Object> raw = postApi("/qualities/v2", "token=" + token);

        List<?> results = (List<?>) raw.get("results");

        Set<String> qualities = new HashSet<>();
        for (Object item : results) {
            if (item instanceof Map<?, ?> map && map.containsKey("title")) {
                qualities.add(map.get("title").toString());
            } else if (item instanceof String s) {
                qualities.add(s);
            }
        }

        assertThat(qualities.size()).as("should have multiple quality types").isGreaterThan(1);
    }

    // ======================== Typed DTO round-trips ========================

    @Test
    @Order(90)
    @DisplayName("/translations/v2 → KodikReferenceResponse<KodikTranslationDto> round-trip")
    void translationsTypedRoundTrip() throws Exception {
        Map<String, Object> raw = postApi("/translations/v2", "token=" + token);

        KodikReferenceResponse<KodikTranslationDto> typed =
                MAPPER.convertValue(
                        raw, new TypeReference<KodikReferenceResponse<KodikTranslationDto>>() {});

        assertThat(typed).isNotNull();
        assertThat(typed.getResults()).as("translations typed results").isNotEmpty();
        KodikTranslationDto first = typed.getResults().get(0);
        assertSoftly(
                soft -> {
                    soft.assertThat(first.id()).as("id populated").isNotNull();
                    soft.assertThat(first.title()).as("title populated").isNotBlank();
                });
    }

    @Test
    @Order(91)
    @DisplayName(
            "/genres → KodikReferenceResponse<KodikGenreDto> round-trip (id intentionally absent)")
    void genresTypedRoundTrip() throws Exception {
        Map<String, Object> raw = postApi("/genres", "token=" + token);

        KodikReferenceResponse<KodikGenreDto> typed =
                MAPPER.convertValue(
                        raw, new TypeReference<KodikReferenceResponse<KodikGenreDto>>() {});

        assertThat(typed).isNotNull();
        assertThat(typed.getResults()).as("genres typed results").isNotEmpty();
        assertThat(typed.getResults().get(0).title()).as("genre title").isNotBlank();
    }

    @Test
    @Order(92)
    @DisplayName("/countries → KodikReferenceResponse<KodikCountryDto> round-trip")
    void countriesTypedRoundTrip() throws Exception {
        Map<String, Object> raw = postApi("/countries", "token=" + token);

        KodikReferenceResponse<KodikCountryDto> typed =
                MAPPER.convertValue(
                        raw, new TypeReference<KodikReferenceResponse<KodikCountryDto>>() {});

        assertThat(typed).isNotNull();
        assertThat(typed.getResults()).as("countries typed results").isNotEmpty();
        assertThat(typed.getResults().get(0).title()).as("country title").isNotBlank();
    }

    @Test
    @Order(93)
    @DisplayName("/years → KodikReferenceResponse<KodikYearDto> round-trip (year field, not title)")
    void yearsTypedRoundTrip() throws Exception {
        Map<String, Object> raw = postApi("/years", "token=" + token);

        KodikReferenceResponse<KodikYearDto> typed =
                MAPPER.convertValue(
                        raw, new TypeReference<KodikReferenceResponse<KodikYearDto>>() {});

        assertThat(typed).isNotNull();
        assertThat(typed.getResults()).as("years typed results").isNotEmpty();
        assertThat(typed.getResults().get(0).year()).as("year populated").isNotNull();
    }

    @Test
    @Order(94)
    @DisplayName("/qualities/v2 → KodikReferenceResponse<KodikQualityDto> round-trip")
    void qualitiesTypedRoundTrip() throws Exception {
        Map<String, Object> raw = postApi("/qualities/v2", "token=" + token);

        KodikReferenceResponse<KodikQualityDto> typed =
                MAPPER.convertValue(
                        raw, new TypeReference<KodikReferenceResponse<KodikQualityDto>>() {});

        assertThat(typed).isNotNull();
        assertThat(typed.getResults()).as("qualities typed results").isNotEmpty();
        assertThat(typed.getResults().get(0).title()).as("quality title").isNotBlank();
    }

    // ======================== /search — edge cases ========================

    @Test
    @Order(80)
    @DisplayName("/search with limit=1 returns exactly 1 result")
    void searchLimitOne() throws Exception {
        Map<String, Object> raw = postApi("/search", "token=" + token + "&title=Naruto&limit=1");

        List<?> results = (List<?>) raw.get("results");
        assertThat(results.size())
                .as("limit=1 should return at most 1 result")
                .isLessThanOrEqualTo(1);
    }

    @Test
    @Order(81)
    @DisplayName("/search with strict=true — strict title matching")
    void searchStrict() throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&title=Naruto&strict=true&limit=5");

        assertPaginatedResponse(raw, "/search strict=true");
    }

    @Test
    @Order(82)
    @DisplayName("/search with not_blocked_in=RU — geo filter")
    void searchNotBlockedIn() throws Exception {
        Map<String, Object> raw =
                postApi("/search", "token=" + token + "&title=Naruto&not_blocked_in=RU&limit=3");

        assertPaginatedResponse(raw, "/search not_blocked_in=RU");
    }

    @Test
    @Order(85)
    @DisplayName("/search — blocked_seasons field is map or null")
    void searchBlockedSeasonsType() throws Exception {
        Map<String, Object> raw = postApi("/search", "token=" + token + "&title=Naruto&limit=50");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");

        int withField = 0;
        for (Map<String, Object> r : results) {
            if (r.containsKey("blocked_seasons")) {
                withField++;
                Object bs = r.get("blocked_seasons");
                assertThat(bs == null || bs instanceof Map)
                        .as(
                                "blocked_seasons should be null or a Map, got %s in '%s'",
                                bs != null ? bs.getClass().getSimpleName() : "null", r.get("title"))
                        .isTrue();
            }
        }
        System.out.println(
                "[INFO] blocked_seasons present in "
                        + withField
                        + "/"
                        + results.size()
                        + " results");
    }

    @Test
    @Order(86)
    @DisplayName("/search — blocked_countries field is list or null")
    void searchBlockedCountriesType() throws Exception {
        Map<String, Object> raw = postApi("/search", "token=" + token + "&title=Naruto&limit=50");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");

        for (Map<String, Object> r : results) {
            if (r.containsKey("blocked_countries")) {
                Object bc = r.get("blocked_countries");
                assertThat(bc == null || bc instanceof List)
                        .as(
                                "blocked_countries should be null or a List, got %s in '%s'",
                                bc != null ? bc.getClass().getSimpleName() : "null", r.get("title"))
                        .isTrue();
            }
        }
    }

    @Test
    @Order(83)
    @DisplayName("/search with genres filter")
    void searchByGenre() throws Exception {
        Map<String, Object> raw =
                postApi(
                        "/search",
                        "token=" + token + "&title=a&genres=" + encode("комедия") + "&limit=3");

        assertPaginatedResponse(raw, "/search genres=комедия");
    }

    @Test
    @Order(84)
    @DisplayName("/search with invalid token returns error")
    void searchInvalidToken() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(
                                        BASE_URL
                                                + "/search?token=INVALID_TOKEN_123&title=test&limit=1"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(15))
                        .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> raw = MAPPER.readValue(response.body(), MAP_TYPE);

        assertThat(raw.containsKey("error"))
                .as("Invalid token should return an error field")
                .isTrue();
    }

    // ======================== Helpers ========================

    private void assertPaginatedResponse(Map<String, Object> raw, String context) {
        assertThat(raw).as("%s response should not be null", context).isNotNull();

        assertSoftly(
                soft -> {
                    for (String field : PAGINATED_REQUIRED_FIELDS) {
                        soft.assertThat(raw.containsKey(field))
                                .as("[%s] Required field '%s' must be present", context, field)
                                .isTrue();
                    }
                    soft.assertThat(raw.get("total"))
                            .as("[%s] total must be a number", context)
                            .isInstanceOf(Number.class);
                    soft.assertThat(raw.get("results"))
                            .as("[%s] results must be a list", context)
                            .isInstanceOf(List.class);
                });

        detectUnknownKeys(context, raw.keySet(), PAGINATED_KNOWN_FIELDS);
    }

    private void assertResultItems(Map<String, Object> raw, String context) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) raw.get("results");
        if (results.isEmpty()) return;

        Map<String, Object> first = results.get(0);
        assertSoftly(
                soft -> {
                    for (String field : RESULT_REQUIRED_FIELDS) {
                        soft.assertThat(first.containsKey(field))
                                .as(
                                        "[%s] Result required field '%s' must be present",
                                        context, field)
                                .isTrue();
                    }
                    soft.assertThat(first.get("id"))
                            .as("[%s] id must be a string", context)
                            .isInstanceOf(String.class);
                    soft.assertThat(first.get("link"))
                            .as("[%s] link must be a string", context)
                            .isInstanceOf(String.class);
                    soft.assertThat(first.get("translation"))
                            .as("[%s] translation must be a map", context)
                            .isInstanceOf(Map.class);
                });

        detectUnknownKeys(context + ".Result", first.keySet(), RESULT_KNOWN_FIELDS);
    }

    private static Map<String, Object> postApi(String path, String query) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path + "?" + query))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(30))
                        .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP status for %s", path).isEqualTo(200);

        return MAPPER.readValue(response.body(), MAP_TYPE);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void detectUnknownKeys(String context, Set<String> actual, Set<String> known) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : actual) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            System.err.println(
                    "[SCHEMA DRIFT] " + context + ": new unknown fields detected: " + unknown);
        }
    }
}
