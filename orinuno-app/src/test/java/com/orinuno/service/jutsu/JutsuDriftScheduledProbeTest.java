package com.orinuno.service.jutsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class JutsuDriftScheduledProbeTest {

    @Mock private JutsuClient jutsuClient;

    private OrinunoProperties properties;
    private JutsuDriftScheduledProbe probe;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getProviders().getJutsu().getDriftProbe().setEnabled(true);
        properties.getProviders().getJutsu().getDriftProbe().setCanonicalSlug("onepuunchman");
        probe = new JutsuDriftScheduledProbe(jutsuClient, properties);
    }

    @Test
    void runProbeExercisesTheThreeCanonicalEndpoints() {
        when(jutsuClient.browseCatalog(anyInt()))
                .thenReturn(Mono.just(new JutsuCatalogPage(List.of(), 1, false)));
        when(jutsuClient.getAnimeInfo("onepuunchman"))
                .thenReturn(
                        Mono.just(
                                new JutsuAnimeInfo(
                                        "onepuunchman",
                                        "x",
                                        null,
                                        null,
                                        Optional.empty(),
                                        List.of(),
                                        Optional.empty(),
                                        Set.of(),
                                        Set.of(),
                                        null,
                                        List.of(),
                                        List.of())));
        when(jutsuClient.getLatestNoticeFeed())
                .thenReturn(Mono.just(new JutsuNoticeFeed(0, List.of())));
        when(jutsuClient.getDriftSnapshot()).thenReturn(new JutsuDriftDetector().snapshot());

        probe.runProbe();

        verify(jutsuClient).browseCatalog(1);
        verify(jutsuClient).getAnimeInfo("onepuunchman");
        verify(jutsuClient).getLatestNoticeFeed();
        verify(jutsuClient).getDriftSnapshot();
        assertThat(probe.runCount()).isEqualTo(1);
        assertThat(probe.failureCount()).isZero();
    }

    @Test
    void runProbeCountsRuntimeExceptionsAsFailuresWithoutRethrowing() {
        when(jutsuClient.browseCatalog(anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("simulated")));

        // Should NOT throw — the probe swallows runtime exceptions so a one-off site outage does
        // not bring down the scheduler thread.
        probe.runProbe();

        assertThat(probe.runCount()).isEqualTo(1);
        assertThat(probe.failureCount()).isEqualTo(1);
    }

    @Test
    void runProbeIncrementsCountersOnEachInvocation() {
        when(jutsuClient.browseCatalog(anyInt()))
                .thenReturn(Mono.just(new JutsuCatalogPage(List.of(), 1, false)));
        when(jutsuClient.getAnimeInfo("onepuunchman"))
                .thenReturn(
                        Mono.just(
                                new JutsuAnimeInfo(
                                        "onepuunchman",
                                        "x",
                                        null,
                                        null,
                                        Optional.empty(),
                                        List.of(),
                                        Optional.empty(),
                                        Set.of(),
                                        Set.of(),
                                        null,
                                        List.of(),
                                        List.of())));
        when(jutsuClient.getLatestNoticeFeed())
                .thenReturn(Mono.just(new JutsuNoticeFeed(0, List.of())));
        when(jutsuClient.getDriftSnapshot()).thenReturn(new JutsuDriftDetector().snapshot());

        probe.runProbe();
        probe.runProbe();
        probe.runProbe();

        assertThat(probe.runCount()).isEqualTo(3);
        assertThat(probe.failureCount()).isZero();
    }
}
