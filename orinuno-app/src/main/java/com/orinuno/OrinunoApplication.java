package com.orinuno;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// ADR 0021 §E2 — orinuno-app owns no @Mapper interfaces (the per-source services
// hold their own L1 mappers; the meter-readonly DS uses JdbcTemplate directly).
// mybatis-spring-boot-starter was dropped from this module's pom in the same
// cleanup pass — production + test classpaths are MyBatis-free.
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("com.orinuno")
public class OrinunoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrinunoApplication.class, args);
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Pool dedicated to short-lived {@link org.springframework.scheduling.annotation.Scheduled}
     * jobs that must remain responsive: parse-request worker tick, stale recovery, token
     * revalidation. Long-running decoder maintenance lives on its own pool (see {@link
     * #decoderMaintenanceScheduler()}) so a stuck mp4 batch can never starve the parse queue. See
     * TECH_DEBT TD-PR-5.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("orinuno-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Pool reserved for the blocking decoder maintenance jobs (link TTL refresh, failed-decode
     * retry). Isolated from {@link #taskScheduler()} so a slow Playwright batch can never block
     * {@code RequestWorker.tick()} from claiming new parse requests. See TECH_DEBT TD-PR-5.
     */
    @Bean(name = "decoderMaintenanceTaskScheduler")
    public TaskScheduler decoderMaintenanceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("orinuno-decoder-maint-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
