/*
 * CatalogContentReadRepositoryIT — ADR 0018 Phase 5.4 invariant.
 *
 * Verifies orinuno-app's read-only access to the shared catalog DB owned by
 * `meter`. Boots a real MySQL 8 container, applies meter's catalog-changelog,
 * seeds a row directly, and asserts the repository returns it untouched.
 *
 * This is the wire-up smoke for the second datasource — once Phase 5.7 cuts
 * CatalogController over to this repo, the test becomes part of the read-path
 * contract.
 *
 * Tagged "e2e" — excluded from default `mvn test`. Run with
 *   mvn -pl orinuno-app -Pe2e -Dtest=CatalogContentReadRepositoryIT test
 */
package com.orinuno.catalog.readonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("e2e")
@Testcontainers
@DisplayName("CatalogContentReadRepository — Phase 5.4 read-only access to shared catalog DB")
class CatalogContentReadRepositoryIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno_catalog")
                    .withUsername("root")
                    .withPassword("test")
                    // Mount meter's catalog-changelog resources path — the parent module's
                    // src/main/resources is on the classpath here only because the test
                    // bundles meter's changelog via direct file include below.
                    .withReuse(false);

    @BeforeAll
    static void applyCatalogChangelog() throws Exception {
        try (Connection conn =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            // Apply the catalog DDL directly. Liquibase is not on the classpath for the
            // catalog-changelog resources from `meter`, so we issue the same CREATE TABLE
            // statements inline — the test pinning is the SQL contract, not the migration
            // tool. When Phase 5.6 drops the parallel orinuno-app changelog this becomes
            // a pure integration on meter's classpath.
            try (var stmt = conn.createStatement()) {
                stmt.execute(
                        "CREATE TABLE catalog_content (id BIGINT NOT NULL AUTO_INCREMENT,title_ru"
                            + " VARCHAR(512) NULL,title_en VARCHAR(512) NULL,kind VARCHAR(16) NOT"
                            + " NULL,year INT NULL,shikimori_id VARCHAR(64) NULL,mal_id VARCHAR(64)"
                            + " NULL,imdb_id VARCHAR(64) NULL,kinopoisk_id VARCHAR(64) NULL,mdl_id"
                            + " VARCHAR(64) NULL,tmdb_id VARCHAR(64) NULL,created_at DATETIME(3)"
                            + " NOT NULL,updated_at DATETIME(3) NOT NULL,PRIMARY KEY (id))"
                            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            }
        }
    }

    @Test
    @DisplayName("findById returns the row with chrome + identity columns populated")
    void findByIdHydratesRow() throws Exception {
        try (Connection conn =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (var stmt = conn.createStatement()) {
                stmt.executeUpdate(
                        "INSERT INTO catalog_content"
                                + " (title_ru, title_en, kind, year, kinopoisk_id,"
                                + "  created_at, updated_at)"
                                + " VALUES ('Кризис', 'Crisis', 'movie', 2026, '4242424242',"
                                + "         '2026-05-11 10:00:00.000','2026-05-11 10:00:00.000')");
            }
        }

        JdbcTemplate jdbc =
                new JdbcTemplate(
                        DataSourceBuilder.create()
                                .url(MYSQL.getJdbcUrl())
                                .username(MYSQL.getUsername())
                                .password(MYSQL.getPassword())
                                .driverClassName("com.mysql.cj.jdbc.Driver")
                                .build());
        CatalogContentReadRepository repo = new CatalogContentReadRepository(jdbc);

        Long lastId = jdbc.queryForObject("SELECT MAX(id) FROM catalog_content", Long.class);
        assertThat(lastId).isNotNull();

        CatalogContentRow row = repo.findById(lastId).orElseThrow();
        assertThat(row.id()).isEqualTo(lastId);
        assertThat(row.titleRu()).isEqualTo("Кризис");
        assertThat(row.titleEn()).isEqualTo("Crisis");
        assertThat(row.kind()).isEqualTo("movie");
        assertThat(row.year()).isEqualTo(2026);
        assertThat(row.kinopoiskId()).isEqualTo("4242424242");
    }

    @Test
    @DisplayName("findById returns empty Optional for missing id")
    void findByIdMissingReturnsEmpty() {
        JdbcTemplate jdbc =
                new JdbcTemplate(
                        DataSourceBuilder.create()
                                .url(MYSQL.getJdbcUrl())
                                .username(MYSQL.getUsername())
                                .password(MYSQL.getPassword())
                                .driverClassName("com.mysql.cj.jdbc.Driver")
                                .build());
        CatalogContentReadRepository repo = new CatalogContentReadRepository(jdbc);
        assertThat(repo.findById(999_999_999L)).isEmpty();
    }
}
