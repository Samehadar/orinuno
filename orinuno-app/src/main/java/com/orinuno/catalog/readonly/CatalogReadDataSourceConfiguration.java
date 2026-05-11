/*
 * CatalogReadDataSourceConfiguration — ADR 0018 Phase 5.4.
 *
 * Wires a second datasource pointing at the shared catalog DB owned by `meter`
 * (write-side). The orinuno-app primary datasource (the legacy `orinuno` schema)
 * stays untouched — Spring Boot's default auto-config still owns the primary
 * DataSource / MyBatis SqlSessionFactory / transaction manager. We only expose a
 * named JdbcTemplate so the auto-config bean slot stays free.
 *
 * Gating: @ConditionalOnProperty on orinuno.catalog-read.url means the bean tree
 * is absent in monolith deploys (where orinuno-app reads catalog from its own
 * schema as today). The skeleton ships dormant; Phase 5.7 wires CatalogController
 * to consume CatalogContentReadRepository once the read-path cutover lands.
 *
 * Future evolution (Phase 6): the same JdbcTemplate gets a Caffeine cache layer
 * (Phase 5.7a) and a Kafka-driven local read-store (Phase 6); the contract this
 * config exposes — a "catalogReadJdbcTemplate" qualifier — stays stable across
 * those swaps.
 */
package com.orinuno.catalog.readonly;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(prefix = "orinuno.catalog-read", name = "url")
public class CatalogReadDataSourceConfiguration {

    /**
     * Read-only JdbcTemplate over the shared catalog schema. The underlying {@link DataSource} is
     * kept method-local (not exposed as a bean) so Spring Boot's auto-configured primary {@code
     * DataSource} bean slot stays untouched.
     *
     * <p>In production the connecting user should be granted SELECT only on {@code catalog_*}
     * (Phase 5.9 — DB user separation). The JdbcTemplate name {@code catalogReadJdbcTemplate} is
     * the stable qualifier the repository layer consumes.
     */
    @Bean(name = "catalogReadJdbcTemplate")
    public JdbcTemplate catalogReadJdbcTemplate(
            @Value("${orinuno.catalog-read.url}") String url,
            @Value("${orinuno.catalog-read.username:orinuno_reader}") String username,
            @Value("${orinuno.catalog-read.password:}") String password,
            @Value("${orinuno.catalog-read.driver-class-name:com.mysql.cj.jdbc.Driver}")
                    String driver) {
        DataSource ds =
                DataSourceBuilder.create()
                        .url(url)
                        .username(username)
                        .password(password)
                        .driverClassName(driver)
                        .build();
        return new JdbcTemplate(ds);
    }
}
