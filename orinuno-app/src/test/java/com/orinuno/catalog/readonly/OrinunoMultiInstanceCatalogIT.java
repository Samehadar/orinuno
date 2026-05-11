/*
 * OrinunoMultiInstanceCatalogIT — ADR 0018 Phase 5.8 invariant.
 *
 * Acceptance gate for "orinuno is horizontally scalable behind a load
 * balancer." Spins up a real MySQL 8 container, simulates TWO orinuno
 * instances by constructing two independent CatalogReadCache beans over
 * the same shared CatalogContentReadRepository, and locks the cache /
 * consistency contract:
 *
 *   1. Hot path — both instances return the seeded row.
 *   2. Per-instance cache isolation — invalidating instance A's cache
 *      does NOT touch instance B's cache.
 *   3. Eventual consistency window — after meter writes a new value to
 *      the shared DB, both instances keep serving the OLD cached value
 *      until their own cache is evicted (bounded by the TTL config).
 *
 * The "behind nginx" routing aspect is delegated to docker-compose +
 * --scale app=N at deploy time; here we lock the read-path semantics
 * that make any number of instances safe.
 *
 * Tagged "e2e" — excluded from default `mvn test`. Run with
 *   mvn -pl orinuno-app -Pe2e -Dtest=OrinunoMultiInstanceCatalogIT test
 * Requires Docker on the host.
 */
package com.orinuno.catalog.readonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
@DisplayName("Phase 5.8 — multi-instance orinuno catalog read-path acceptance")
class OrinunoMultiInstanceCatalogIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno_catalog")
                    .withUsername("appuser")
                    .withPassword("apppw");

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = openConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE catalog_content ("
                            + "id BIGINT NOT NULL AUTO_INCREMENT,"
                            + "title_ru VARCHAR(512) NULL,"
                            + "title_en VARCHAR(512) NULL,"
                            + "kind VARCHAR(16) NOT NULL,"
                            + "year INT NULL,"
                            + "shikimori_id VARCHAR(64) NULL,"
                            + "mal_id VARCHAR(64) NULL,"
                            + "imdb_id VARCHAR(64) NULL,"
                            + "kinopoisk_id VARCHAR(64) NULL,"
                            + "mdl_id VARCHAR(64) NULL,"
                            + "tmdb_id VARCHAR(64) NULL,"
                            + "created_at DATETIME(3) NOT NULL,"
                            + "updated_at DATETIME(3) NOT NULL,"
                            + "PRIMARY KEY (id))");
        }
    }

    @BeforeEach
    void truncate() throws Exception {
        try (Connection conn = openConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE catalog_content");
        }
    }

    @Test
    @DisplayName("two instances independently serve the same seeded row")
    void bothInstancesReturnSeededRow() throws Exception {
        seed("Кризис v1");

        CatalogReadCache instanceA = newCacheInstance();
        CatalogReadCache instanceB = newCacheInstance();

        long id = latestId();
        CatalogContentRow rowA = instanceA.findById(id).orElseThrow();
        CatalogContentRow rowB = instanceB.findById(id).orElseThrow();

        assertThat(rowA).isEqualTo(rowB);
        assertThat(rowA.titleRu()).isEqualTo("Кризис v1");
    }

    @Test
    @DisplayName("evictById on one instance leaves the other instance's cache intact")
    void evictionIsPerInstance() throws Exception {
        seed("Кризис v1");
        long id = latestId();

        CatalogReadCache instanceA = newCacheInstance();
        CatalogReadCache instanceB = newCacheInstance();

        // Prime both caches with v1
        assertThat(instanceA.findById(id).orElseThrow().titleRu()).isEqualTo("Кризис v1");
        assertThat(instanceB.findById(id).orElseThrow().titleRu()).isEqualTo("Кризис v1");

        // Simulate a meter-side write that flips the row to v2
        updateTitleTo(id, "Кризис v2");

        // Evict only instance A — B keeps the stale entry
        instanceA.evictById(id);

        assertThat(instanceA.findById(id).orElseThrow().titleRu())
                .as("instance A re-loads from DB after eviction → fresh v2")
                .isEqualTo("Кризис v2");
        assertThat(instanceB.findById(id).orElseThrow().titleRu())
                .as("instance B still serves cached v1 — caches are independent")
                .isEqualTo("Кризис v1");
    }

    @Test
    @DisplayName("after meter writes, stale window is bounded — both instances eventually converge")
    void eventuallyConvergesAfterEvictionOnAllInstances() throws Exception {
        seed("Кризис v1");
        long id = latestId();

        CatalogReadCache instanceA = newCacheInstance();
        CatalogReadCache instanceB = newCacheInstance();

        instanceA.findById(id);
        instanceB.findById(id);

        updateTitleTo(id, "Кризис v2");

        // Eviction on every instance (or TTL expiry — same effect) → convergence.
        instanceA.evictById(id);
        instanceB.evictById(id);

        assertThat(instanceA.findById(id).orElseThrow().titleRu()).isEqualTo("Кризис v2");
        assertThat(instanceB.findById(id).orElseThrow().titleRu()).isEqualTo("Кризис v2");
    }

    private static CatalogReadCache newCacheInstance() {
        JdbcTemplate jdbc =
                new JdbcTemplate(
                        DataSourceBuilder.create()
                                .url(MYSQL.getJdbcUrl())
                                .username(MYSQL.getUsername())
                                .password(MYSQL.getPassword())
                                .driverClassName("com.mysql.cj.jdbc.Driver")
                                .build());
        CatalogContentReadRepository repo = new CatalogContentReadRepository(jdbc);
        // Long TTLs — test asserts behaviour at the API surface, not Caffeine's
        // expiration mechanics.
        return new CatalogReadCache(repo, 3600, 600, 1024);
    }

    private static void seed(String titleRu) throws SQLException {
        try (Connection conn = openConnection();
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "INSERT INTO catalog_content (title_ru, kind, year, created_at, updated_at) "
                            + "VALUES ('"
                            + titleRu
                            + "', 'movie', 2026, NOW(3), NOW(3))");
        }
    }

    private static long latestId() throws SQLException {
        try (Connection conn = openConnection();
                Statement stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT MAX(id) FROM catalog_content")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void updateTitleTo(long id, String newTitle) throws SQLException {
        try (Connection conn = openConnection();
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "UPDATE catalog_content SET title_ru = '"
                            + newTitle
                            + "', updated_at = NOW(3) WHERE id = "
                            + id);
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
