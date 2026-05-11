/*
 * JutsuChangelogApplyIT — ADR 0019 Phase 4.2 smoke.
 *
 * Boots a real MySQL 8 container, points Liquibase at this module's
 * db/changelog/liquibase-changelog.yaml, and asserts:
 *
 *   - the four jutsu_* tables exist after update()
 *   - DATABASECHANGELOG row count == number of <include> entries
 *
 * Locks the contract that fresh deploys of orinuno-source-jutsu against an
 * empty orinuno_source_jutsu schema bring up the jutsu tables without
 * help from orinuno-app.
 *
 * Tagged "e2e" — excluded from default `mvn test`. Run with
 *   mvn -pl orinuno-source-jutsu -Dgroups=e2e -Dtest=JutsuChangelogApplyIT test
 * Requires Docker on the host.
 */
package com.orinuno.source.jutsu;

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
@DisplayName("orinuno-source-jutsu changelog — Phase 4.2 apply against fresh MySQL")
class JutsuChangelogApplyIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno_source_jutsu")
                    .withUsername("root")
                    .withPassword("test");

    @Test
    @DisplayName(
            "Liquibase update creates jutsu_title + jutsu_episode + jutsu_sync_state + jutsu_film")
    void changelogApplies() throws Exception {
        try (Connection conn = openConnection()) {
            Database database =
                    DatabaseFactory.getInstance()
                            .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            Liquibase liquibase =
                    new Liquibase(
                            "db/changelog/liquibase-changelog.yaml",
                            new ClassLoaderResourceAccessor(),
                            database);
            liquibase.update("");
        }

        try (Connection conn = openConnection()) {
            List<String> tables = listTables(conn);
            assertThat(tables)
                    .as("Phase 4.2 changelog must create the four jutsu_* tables")
                    .contains("jutsu_title", "jutsu_episode", "jutsu_sync_state", "jutsu_film");

            int applied = countAppliedChangesets(conn);
            assertThat(applied)
                    .as("DATABASECHANGELOG rows match the changelog <include> count")
                    .isEqualTo(6);
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
