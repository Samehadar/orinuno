/*
 * JutsuRepositoriesSmokeIT — ADR 0019 Phase 4.3 invariant.
 *
 * Boots a real MySQL 8 container with Liquibase applied via Spring Boot
 * autoconfiguration; exercises each of the four jut.su repositories at the
 * SQL boundary to lock the @MapperScan + XML namespace bindings that
 * Phase 4.3 introduces:
 *
 *   - JutsuTitleRepository    upsert + findBySlug round-trip
 *   - JutsuEpisodeRepository  bulk-upsert + findBySlug
 *   - JutsuFilmRepository     bulk-upsert + findBySlug
 *   - JutsuSyncStateRepository initIfAbsent singleton + findSingleton
 *
 * Tagged "e2e" — excluded from default `mvn test`. Run with
 *   mvn -pl orinuno-source-jutsu -Pe2e -Dtest=JutsuRepositoriesSmokeIT test
 */
package com.orinuno.source.jutsu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.source.jutsu.model.JutsuEpisode;
import com.orinuno.source.jutsu.model.JutsuFilm;
import com.orinuno.source.jutsu.model.JutsuSyncState;
import com.orinuno.source.jutsu.model.JutsuTitle;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
@DisplayName("orinuno-source-jutsu — Phase 4.3 repository smoke")
class JutsuRepositoriesSmokeIT {

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

    @Autowired private JutsuTitleRepository titleRepository;
    @Autowired private JutsuEpisodeRepository episodeRepository;
    @Autowired private JutsuFilmRepository filmRepository;
    @Autowired private JutsuSyncStateRepository syncStateRepository;

    @Test
    @DisplayName("JutsuTitleRepository.upsert + findBySlug round-trip")
    void titleRoundTrip() {
        LocalDateTime now = LocalDateTime.now();
        JutsuTitle row =
                JutsuTitle.builder()
                        .slug("naruto")
                        .title("Наруто")
                        .originalTitle("Naruto")
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();

        titleRepository.upsert(row);

        Optional<JutsuTitle> found = titleRepository.findBySlug("naruto");
        assertThat(found).isPresent();
        assertThat(found.get().getSlug()).isEqualTo("naruto");
        assertThat(found.get().getTitle()).isEqualTo("Наруто");
    }

    @Test
    @DisplayName("JutsuSyncStateRepository singleton initIfAbsent + findSingleton")
    void syncStateRoundTrip() {
        JutsuSyncState seed = new JutsuSyncState();
        seed.setUpdatedAt(LocalDateTime.now());
        syncStateRepository.initIfAbsent(seed);
        syncStateRepository.initIfAbsent(seed); // INSERT IGNORE — idempotent

        Optional<JutsuSyncState> snapshot = syncStateRepository.findSingleton();
        assertThat(snapshot).isPresent();
    }

    @Test
    @DisplayName("JutsuEpisodeRepository.upsertAll + findBySlug round-trip")
    void episodesRoundTrip() {
        LocalDateTime now = LocalDateTime.now();
        JutsuTitle title =
                JutsuTitle.builder()
                        .slug("test-anime")
                        .title("Test Anime")
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();
        titleRepository.upsert(title);

        JutsuEpisode ep =
                JutsuEpisode.builder()
                        .slug("test-anime")
                        .season(1)
                        .episode(1)
                        .relativeUrl("/anime/test-anime/season-1/episode-1.html")
                        .discoveredAt(now)
                        .lastSeenAt(now)
                        .build();
        episodeRepository.upsertAll(List.of(ep));

        List<JutsuEpisode> found = episodeRepository.findBySlug("test-anime");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getSeason()).isEqualTo(1);
        assertThat(found.get(0).getEpisode()).isEqualTo(1);
    }

    @Test
    @DisplayName("JutsuFilmRepository.upsertAll + findBySlug round-trip")
    void filmsRoundTrip() {
        LocalDateTime now = LocalDateTime.now();
        JutsuTitle title =
                JutsuTitle.builder()
                        .slug("test-film")
                        .title("Test Film")
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();
        titleRepository.upsert(title);

        JutsuFilm film =
                JutsuFilm.builder()
                        .slug("test-film")
                        .filmIndex(1)
                        .relativeUrl("/anime/test-film/film-1.html")
                        .discoveredAt(now)
                        .lastSeenAt(now)
                        .build();
        filmRepository.upsertAll(List.of(film));

        List<JutsuFilm> found = filmRepository.findBySlug("test-film");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getFilmIndex()).isEqualTo(1);
    }
}
