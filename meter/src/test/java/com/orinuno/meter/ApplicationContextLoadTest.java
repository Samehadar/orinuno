/*
 * ApplicationContextLoadTest — ADR 0018 Phase 5.1.
 *
 * Smoke test: the meter skeleton boots a Spring context without runtime errors.
 *
 * Excludes the auto-configurations that would require an actual MySQL instance to be
 * up — the skeleton has no Liquibase changelog yet (Phase 5.2 brings the catalog_*
 * changelogs over from orinuno-app) and no @Mapper interfaces yet (Phases 5.3+
 * move CatalogContentRepository etc. into this module). Once those land this test
 * grows into an integration test against a Testcontainers MySQL.
 */
package com.orinuno.meter;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = Application.class,
        properties = {
            "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration,"
                + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
        })
class ApplicationContextLoadTest {

    @Test
    void contextLoads() {
        // pass = Spring context built without runtime errors
        // Static reference forces the compiler to keep the auto-config imports honest —
        // if Spring renames any of these classes the build fails fast.
        Class<?>[] excluded = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            LiquibaseAutoConfiguration.class,
            MybatisAutoConfiguration.class,
        };
        assert excluded.length == 5;
    }
}
