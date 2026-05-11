/*
 * CatalogReaderGrantIT — ADR 0018 Phase 5.9 invariant.
 *
 * Boots a real MySQL 8 container, applies the grants script from
 * scripts/db-init/02-create-catalog-grants.sql, and asserts:
 *
 *   1. orinuno_catalog_reader can SELECT from catalog_*
 *   2. orinuno_catalog_reader CANNOT INSERT — MySQL rejects with 1142
 *      "Access denied; you need ... INSERT privilege"
 *
 * Defense-in-depth: even if a future code change injects a write through the
 * read-only datasource, the DB user's grants stop it cold. This test pins
 * that boundary so a permissive accidental GRANT cannot silently sneak in.
 *
 * Tagged "e2e" — excluded from default `mvn test`. Run with
 *   mvn -pl orinuno-app -Pe2e -Dtest=CatalogReaderGrantIT test
 */
package com.orinuno.catalog.readonly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("e2e")
@Testcontainers
@DisplayName("Phase 5.9 — orinuno_catalog_reader grant is SELECT-only")
class CatalogReaderGrantIT {

    // Use the container's true MYSQL root (auto-created by mysql:8.0 with
    // MYSQL_ROOT_PASSWORD=test) for the CREATE USER + GRANT bootstrap. The
    // testcontainer's `withUsername("root")` would create a *non-root* test
    // user named "root" without GRANT privilege, which fails the grants script.
    // The grants script needs MySQL's superuser (CREATE USER + GRANT). Mount
    // the script into /docker-entrypoint-initdb.d/ so MySQL's official init
    // phase runs it as the container's bootstrap root before the daemon
    // accepts JDBC connections. testcontainers' withInitScript() runs after
    // boot as the non-root `appuser`, which fails CREATE USER.
    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("orinuno_catalog")
                    .withUsername("appuser")
                    .withPassword("apppw")
                    .withClasspathResourceMapping(
                            "db-init/02-create-catalog-grants.sql",
                            "/docker-entrypoint-initdb.d/02-create-catalog-grants.sql",
                            BindMode.READ_ONLY);

    @BeforeAll
    static void bootstrap() throws Exception {
        // Grants are applied during container init via .withInitScript(). Here
        // we just create the catalog_content table — appuser owns the orinuno_catalog
        // database so the CREATE TABLE works without GRANT.
        try (Connection conn = appConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TABLE catalog_content ("
                            + "id BIGINT NOT NULL AUTO_INCREMENT,"
                            + "title_ru VARCHAR(512) NULL,"
                            + "kind VARCHAR(16) NOT NULL,"
                            + "created_at DATETIME(3) NOT NULL,"
                            + "updated_at DATETIME(3) NOT NULL,"
                            + "PRIMARY KEY (id))");
        }
    }

    @Test
    @DisplayName("orinuno_catalog_reader can SELECT")
    void readerCanSelect() throws Exception {
        try (Connection conn = readerConnection();
                Statement stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM catalog_content")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("orinuno_catalog_reader cannot INSERT — MySQL rejects with grant error")
    void readerCannotInsert() {
        assertThatThrownBy(
                        () -> {
                            try (Connection conn = readerConnection();
                                    Statement stmt = conn.createStatement()) {
                                stmt.executeUpdate(
                                        "INSERT INTO catalog_content (title_ru, kind, created_at,"
                                                + " updated_at) VALUES ('attempt', 'movie', NOW(3),"
                                                + " NOW(3))");
                            }
                        })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("INSERT");
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "appuser", "apppw");
    }

    private static Connection readerConnection() throws SQLException {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "orinuno_catalog_reader", "reader_pw");
    }
}
