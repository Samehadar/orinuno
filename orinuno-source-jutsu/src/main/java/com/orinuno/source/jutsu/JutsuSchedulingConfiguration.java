/*
 * JutsuSchedulingConfiguration — TaskScheduler beans for the sync workers (Phase 4.5).
 *
 * Mirrors orinuno-app's decoder-maintenance pool: a dedicated thread pool
 * separate from the main Spring scheduler so the long-running catalog crawl
 * (30 pages × 1 RPS = ~30 s) doesn't starve other @Scheduled jobs.
 */
package com.orinuno.source.jutsu;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class JutsuSchedulingConfiguration {

    /**
     * UTC system clock shared by scheduled jobs + the SourceCatalogEvent
     * projection. @ConditionalOnMissingBean so tests can swap in a fixed clock.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Dedicated pool for jut.su sync workers (full-crawl + notice-walk). 2 threads is plenty — the
     * two schedulers never overlap heavily because both honor the SDK's 1 RPS budget.
     */
    @Bean(name = "decoderMaintenanceTaskScheduler", destroyMethod = "shutdown")
    public TaskScheduler decoderMaintenanceTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("jutsu-sync-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        return scheduler;
    }
}
