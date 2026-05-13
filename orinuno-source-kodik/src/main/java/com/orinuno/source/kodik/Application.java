/*
 * orinuno-source-kodik — standalone Kodik per-source service.
 *
 * ADR 0018 Phase 2.1 — module skeleton. Subsequent phases (2.2 onwards) lift the kodik_*
 * Liquibase changelog, MyBatis mappers, REST controllers, parse-request queue, and the
 * full KodikVideoDecoderService stack out of orinuno-app and into this module. The Spring
 * Boot fat jar (orinuno-source-kodik.jar) is the unit of independent deploy.
 */
package com.orinuno.source.kodik;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Entry point for the orinuno-source-kodik service. {@code kodik-sdk-spring-boot-starter} on the
 * classpath provides the SDK auto-wiring (HTTP client / rate limiter / token registry / decoder
 * metrics); this application bootstraps the Spring context, owns Liquibase migrations against its
 * dedicated MySQL schema, and exposes the REST surface.
 *
 * <p>{@link EnableScheduling} unlocks the auto-config's token revalidation tick.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("com.orinuno.source.kodik.configuration")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * UTC system clock for parse-request queue services + future decoder helpers (ADR 0021 §D1a).
     * Conditional so tests can override with a fixed Clock.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Dedicated pool for the long-running maintenance jobs: {@code CalendarDeltaScheduler} (CAL-6
     * watcher), {@code DumpScheduler} (DUMP-1 HEAD poller), and decoder maintenance ticks. Same
     * pattern orinuno-app + orinuno-source-jutsu use — separate pool so a slow Kodik response can
     * never starve Spring's default scheduler. 2 threads matches the legacy orinuno-app sizing.
     */
    @Bean(name = "decoderMaintenanceTaskScheduler", destroyMethod = "shutdown")
    public TaskScheduler decoderMaintenanceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("kodik-decoder-maint-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
