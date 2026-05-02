package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orinuno.client.KodikApiClient;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.repository.EpisodeVariantRepository;
import com.orinuno.service.decoder.KodikDecodeOrchestrator;
import com.orinuno.service.metrics.KodikCdnHostMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DecoderMaintenanceSchedulerTest {

    @Mock private KodikApiClient kodikApiClient;
    @Mock private ContentService contentService;
    @Mock private KodikVideoDecoderService decoderService;
    @Mock private KodikDecodeOrchestrator decodeOrchestrator;
    @Mock private EpisodeVariantRepository episodeVariantRepository;

    private ThreadPoolTaskScheduler scheduler;
    private DecoderMaintenanceScheduler maintenance;

    @AfterEach
    void tearDown() {
        if (maintenance != null) {
            maintenance.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName(
            "Maintenance jobs run on the dedicated decoder pool, not on the default scheduler"
                    + " (TD-PR-5)")
    void runsOnDedicatedPool() {
        OrinunoProperties props = new OrinunoProperties();
        props.getKodik().setRequestDelayMs(0);
        props.getDecoder().setRefreshIntervalMs(120); // <-- runs every 120ms in test
        props.getDecoder().getMaintenance().setMaxBatchPerTick(1);

        AtomicInteger refreshCalls = new AtomicInteger();
        Mockito.when(episodeVariantRepository.findExpiredLinks(Mockito.anyInt(), Mockito.anyInt()))
                .thenAnswer(
                        invocation -> {
                            assertThat(Thread.currentThread().getName())
                                    .startsWith("orinuno-decoder-maint-");
                            refreshCalls.incrementAndGet();
                            return List.of();
                        });
        Mockito.when(episodeVariantRepository.findFailedDecode(Mockito.anyInt()))
                .thenReturn(List.of());

        SimpleMeterRegistry sharedRegistry = new SimpleMeterRegistry();
        ParserService parser =
                new ParserService(
                        kodikApiClient,
                        contentService,
                        decoderService,
                        decodeOrchestrator,
                        episodeVariantRepository,
                        props,
                        new KodikCdnHostMetrics(sharedRegistry),
                        new com.orinuno.service.metrics.KodikDecoderMetrics(sharedRegistry),
                        null);

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("orinuno-decoder-maint-");
        scheduler.initialize();

        maintenance = new DecoderMaintenanceScheduler(parser, props, scheduler);
        maintenance.start();

        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(refreshCalls.get()).isGreaterThanOrEqualTo(1));
    }
}
