/*
 * meter — Application entry point (ADR 0018 Phase 5.1).
 *
 * OSS-side catalog collector. Subscribes to /api/v1/source-events/ready on every
 * per-source service, reconciles events through CatalogIdentityResolver, and is the
 * single writer of the shared catalog_* MySQL schema. orinuno (multi-instance reader)
 * sees catalog state by SELECT-only MyBatis mappers against the same DB.
 *
 * This is just the deployable skeleton — the catalog write-path (CatalogIngestionService,
 * CatalogIdentityResolver, *RemoteEventPoller) moves over from orinuno-app in
 * Phases 5.3 + 5.5. Until then, this Application boots a healthy actuator endpoint and
 * not much else.
 */
package com.orinuno.meter;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan(basePackages = {"com.orinuno.meter.catalog.repository", "com.orinuno.meter.poller"})
public class Application {

    /** UTC system clock shared by the poller. Overridable in tests via fixed Clock. */
    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
