/*
 * JutsuSourceEventProjectionIT — ADR 0019 Phase 4.6 invariant.
 *
 * Boots a real MySQL 8 container with Liquibase applied, seeds a
 * JutsuTitle + episodes + film via the live repositories, and asserts the
 * projection turns them into the right SourceCatalogEvent variants.
 *
 * Tagged "e2e". Run with
 *   mvn -pl orinuno-source-jutsu -Pe2e -Dtest=JutsuSourceEventProjectionIT test
 */
package com.orinuno.source.jutsu.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.source.jutsu.model.JutsuEpisode;
import com.orinuno.source.jutsu.model.JutsuFilm;
import com.orinuno.source.jutsu.model.JutsuTitle;
import com.orinuno.source.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.source.jutsu.repository.JutsuFilmRepository;
import com.orinuno.source.jutsu.repository.JutsuTitleRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("e2e")
@Testcontainers
@SpringBootTest
@DisplayName("JutsuSourceEventProjection — Phase 4.6 ready-event stream")
class JutsuSourceEventProjectionIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno_source_jutsu")
                    .withUsername("root")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private JutsuSourceEventProjection projection;
    @Autowired private JutsuTitleRepository titleRepository;
    @Autowired private JutsuEpisodeRepository episodeRepository;
    @Autowired private JutsuFilmRepository filmRepository;

    @Test
    @DisplayName("title with episodes → SeriesDiscovered; episodeless film row → MovieDiscovered")
    void renderEvents() {
        LocalDateTime now = LocalDateTime.now();
        // Series row
        JutsuTitle series =
                JutsuTitle.builder()
                        .slug("naruto")
                        .title("Наруто")
                        .originalTitle("Naruto")
                        .yearBucket("2002")
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();
        titleRepository.upsert(series);
        episodeRepository.upsertAll(
                List.of(
                        JutsuEpisode.builder()
                                .slug("naruto")
                                .season(1)
                                .episode(1)
                                .relativeUrl("/anime/naruto/season-1/episode-1.html")
                                .discoveredAt(now)
                                .lastSeenAt(now)
                                .build(),
                        JutsuEpisode.builder()
                                .slug("naruto")
                                .season(1)
                                .episode(2)
                                .relativeUrl("/anime/naruto/season-1/episode-2.html")
                                .discoveredAt(now)
                                .lastSeenAt(now)
                                .build()));
        // Film row
        JutsuTitle film =
                JutsuTitle.builder()
                        .slug("the-last-naruto")
                        .title("Наруто: Последний фильм")
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();
        titleRepository.upsert(film);
        filmRepository.upsertAll(
                List.of(
                        JutsuFilm.builder()
                                .slug("the-last-naruto")
                                .filmIndex(1)
                                .relativeUrl("/anime/the-last-naruto/film-1.html")
                                .discoveredAt(now)
                                .lastSeenAt(now)
                                .build()));

        List<SourceCatalogEvent> events = projection.findReadyEvents(null, 10);
        assertThat(events).hasSize(2);

        SourceCatalogEvent forSeries =
                events.stream()
                        .filter(e -> e.identifier().sourceId().equals("naruto"))
                        .findFirst()
                        .orElseThrow();
        assertThat(forSeries).isInstanceOf(SourceCatalogEvent.SeriesDiscovered.class);
        SourceCatalogEvent.SeriesDiscovered s = (SourceCatalogEvent.SeriesDiscovered) forSeries;
        assertThat(s.identifier().sourceType()).isEqualTo("jutsu");
        assertThat(s.seasons()).hasSize(1);
        assertThat(s.seasons().get(0).episodes()).hasSize(2);

        SourceCatalogEvent forFilm =
                events.stream()
                        .filter(e -> e.identifier().sourceId().equals("the-last-naruto"))
                        .findFirst()
                        .orElseThrow();
        assertThat(forFilm).isInstanceOf(SourceCatalogEvent.MovieDiscovered.class);
    }
}
