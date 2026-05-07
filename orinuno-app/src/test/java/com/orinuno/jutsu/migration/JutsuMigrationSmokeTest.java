package com.orinuno.jutsu.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * E2E smoke test for the ADR 0016 P1a migrations. Boots a MySQL 8 container, runs Liquibase
 * directly off the bundled changelog, asserts the three jut.su tables exist with the documented
 * indexes. Tagged {@code "e2e"} so default {@code mvn test} skips it; run with {@code -Pe2e}.
 */
@Tag("e2e")
@Testcontainers
class JutsuMigrationSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno")
                    .withUsername("orinuno")
                    .withPassword("orinuno")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci");

    @Test
    @DisplayName(
            "Liquibase boots the bundled changelog into a clean MySQL container; jut.su tables and"
                    + " indexes exist")
    void migrationBootstraps() throws Exception {
        // Apply migrations on one connection, then drop it (Liquibase's try-with-resources closes
        // the underlying JDBC connection on close()) — assertions get their own.
        try (Connection conn =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            Database db =
                    DatabaseFactory.getInstance()
                            .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase =
                    new Liquibase(
                            "com/orinuno/db/changelog/liquibase-changelog.yaml",
                            new ClassLoaderResourceAccessor(),
                            db)) {
                liquibase.update("default");
            }
        }

        try (Connection conn =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            DatabaseMetaData meta = conn.getMetaData();
            assertThat(tableExists(meta, "jutsu_title")).as("jutsu_title table").isTrue();
            assertThat(tableExists(meta, "jutsu_episode")).as("jutsu_episode table").isTrue();
            assertThat(tableExists(meta, "jutsu_sync_state")).as("jutsu_sync_state table").isTrue();

            assertThat(columns(conn, "jutsu_title"))
                    .contains(
                            "slug",
                            "title_ru",
                            "title_en",
                            "status",
                            "year",
                            "episodes_total",
                            "shikimori_id",
                            "mal_id",
                            "description",
                            "poster_url",
                            "last_synced_at",
                            "source_etag");

            assertThat(indexNames(conn, "jutsu_title"))
                    .contains(
                            "idx_jutsu_title_shikimori_id",
                            "idx_jutsu_title_mal_id",
                            "idx_jutsu_title_last_synced_at",
                            "idx_jutsu_title_status");

            assertThat(columns(conn, "jutsu_episode"))
                    .contains(
                            "title_slug",
                            "season",
                            "episode",
                            "embed_url",
                            "video_qualities",
                            "last_synced_at");

            try (ResultSet rs =
                    conn.createStatement().executeQuery("SELECT COUNT(*) FROM jutsu_sync_state")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static boolean tableExists(DatabaseMetaData meta, String tableName) throws Exception {
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[] {"TABLE"})) {
            return rs.next();
        }
    }

    private static Set<String> columns(Connection conn, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, "%")) {
            while (rs.next()) names.add(rs.getString("COLUMN_NAME"));
        }
        return names;
    }

    private static Set<String> indexNames(Connection conn, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getIndexInfo(null, null, table, false, true)) {
            while (rs.next()) {
                String n = rs.getString("INDEX_NAME");
                if (n != null) names.add(n);
            }
        }
        return names;
    }
}
