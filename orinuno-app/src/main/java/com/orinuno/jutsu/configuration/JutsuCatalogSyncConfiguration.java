package com.orinuno.jutsu.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.JutsuSyncProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import com.orinuno.jutsu.sync.JutsuCatalogScheduler;
import com.orinuno.jutsu.sync.JutsuCatalogSyncService;
import com.orinuno.jutsu.sync.JutsuNoticeLockService;
import com.orinuno.jutsu.sync.JutsuStalenessTracker;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring wiring for the jut.su catalog sync subsystem (ADR 0016 P1a). Owns:
 *
 * <ul>
 *   <li>{@link JutsuCatalogSyncService} — the actual full-crawl + notice-incremental logic.
 *   <li>{@link JutsuCatalogScheduler} — thin {@code @Scheduled} façade.
 *   <li>{@link #jutsuSyncTaskScheduler()} — dedicated 2-thread pool. Mirrors {@code
 *       decoderMaintenanceTaskScheduler} per TD-PR-5: a stuck jut.su batch must never starve
 *       parse-queue ticks (i.e. they MUST NOT share a pool).
 * </ul>
 *
 * <p>Live-fallback wiring lives in its own {@link JutsuLiveFallbackConfiguration}.
 *
 * <p>The scheduler is created with {@link ConditionalOnProperty} so tests / batch jobs can disable
 * it via {@code orinuno.jutsu.sync.enabled=false}.
 */
@Configuration
public class JutsuCatalogSyncConfiguration {

    @Bean
    public JutsuCatalogSyncService jutsuCatalogSyncService(
            JutsuClient jutsuClient,
            JutsuTitleRepository titleRepository,
            JutsuEpisodeRepository episodeRepository,
            JutsuNoticeLockService lockService,
            JutsuStalenessTracker stalenessTracker,
            JutsuSyncProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        return new JutsuCatalogSyncService(
                jutsuClient,
                titleRepository,
                episodeRepository,
                lockService,
                stalenessTracker,
                properties,
                objectMapper,
                clock);
    }

    @Bean
    @ConditionalOnProperty(
            value = "orinuno.jutsu.sync.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public JutsuCatalogScheduler jutsuCatalogScheduler(JutsuCatalogSyncService syncService) {
        return new JutsuCatalogScheduler(syncService);
    }

    @Bean(name = "jutsuSyncTaskScheduler")
    public TaskScheduler jutsuSyncTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("orinuno-jutsu-sync-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
