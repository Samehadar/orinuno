/*
 * CatalogChangelogApplyIT — ADR 0018 Phase 5.2a smoke.
 *
 * Boots a real MySQL 8 container, points Liquibase at meter's
 * db/catalog-changelog/liquibase-changelog.yaml, and asserts:
 *
 *   - the four catalog_* tables exist after update()
 *   - the DATABASECHANGELOG row count matches the changelog include list
 *
 * Locks the contract that fresh deploys of `meter` against an empty
 * orinuno_catalog schema bring up the canonical catalog tables without
 * help from orinuno-app. orinuno-app keeps a parallel copy of the same
 * SQL in its own schema until Phase 5.4 cuts the read-path over.
 *
 * Tagged "e2e" — excluded from `mvn test`. Run with
 *   mvn -pl meter test -Dgroups=e2e -Dtest=CatalogChangelogApplyIT
 * Requires Docker on the host.
 */
package com.orinuno.meter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
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

@Tag("e2e")
@Testcontainers
@DisplayName("Meter catalog changelog — Phase 5.2a apply against fresh MySQL")
class CatalogChangelogApplyIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno_catalog")
                    .withUsername("root")
                    .withPassword("test");

    @Test
    @DisplayName("Liquibase update creates catalog_* + L2 episode_source/video + watermark tables")
    void changelogApplies() throws Exception {
        // Liquibase closes its JDBC connection on update() / close(), so the
        // SHOW TABLES + DATABASECHANGELOG queries below need a fresh connection
        // rather than reusing the one we handed to Liquibase.
        try (Connection conn = openConnection()) {
            Database database =
                    DatabaseFactory.getInstance()
                            .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            Liquibase liquibase =
                    new Liquibase(
                            "db/catalog-changelog/liquibase-changelog.yaml",
                            new ClassLoaderResourceAccessor(),
                            database);
            liquibase.update("");
        }

        try (Connection conn = openConnection()) {
            List<String> tables = listTables(conn);
            assertThat(tables)
                    .as(
                            "meter changelog must create catalog_* (L3) + episode_source/video (L2)"
                                    + " + remote_source_watermark")
                    .contains(
                            "catalog_content",
                            "catalog_content_external_id",
                            "catalog_episode",
                            "catalog_episode_source_link",
                            "orinuno_remote_source_watermark",
                            "episode_source",
                            "episode_video");

            // DATABASECHANGELOG must hold one row per <include> entry, no more
            // (locks the "no rogue inline changeset" invariant).
            int applied = countAppliedChangesets(conn);
            assertThat(applied)
                    .as("DATABASECHANGELOG row count == number of <include> entries")
                    .isEqualTo(7);
        }
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static List<String> listTables(Connection conn) throws Exception {
        List<String> tables = new ArrayList<>();
        try (var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static int countAppliedChangesets(Connection conn) throws Exception {
        try (var stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM DATABASECHANGELOG")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
