package com.orinuno.service.jutsu;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.drift.JutsuDriftSnapshot;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic canary that exercises a small fixed set of jut.su endpoints to keep the SDK drift
 * detector populated even on quiet days when no user traffic flows through the SDK.
 *
 * <p>The probe is opt-in: enable with {@code orinuno.providers.jutsu.drift-probe.enabled=true}.
 * Runs every {@code orinuno.providers.jutsu.drift-probe.interval-minutes} (default 6 h).
 *
 * <p><strong>Why a probe?</strong> The {@link JutsuDriftSnapshot} is read by {@link
 * com.orinuno.service.orchestration.MultiSourceRanker} to demote jut.su when the underlying HTML
 * starts diverging from our parsers. On a healthy day the SDK isn't called at all if no jut.su
 * episodes are being decoded; without an active probe the snapshot stays stale and we wouldn't
 * notice when, say, jut.su renames the {@code .all_anime_global} card class. The canary acts as the
 * safety net.
 *
 * <p>The probe uses lenient parsing (the SDK's default mode) — it deliberately does NOT throw on
 * the first drift signal, because the goal is to populate the detector window so the ranker can
 * react, not to tear the application down. Strict parsing is reserved for the unit-test fixture
 * replay; the live site is too jittery for that.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "orinuno.providers.jutsu.drift-probe.enabled", havingValue = "true")
public class JutsuDriftScheduledProbe {

    private final JutsuClient jutsuClient;
    private final OrinunoProperties properties;
    private final AtomicLong runCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);

    public JutsuDriftScheduledProbe(JutsuClient jutsuClient, OrinunoProperties properties) {
        this.jutsuClient = jutsuClient;
        this.properties = properties;
    }

    @PostConstruct
    void announce() {
        OrinunoProperties.JutsuProperties.DriftProbeProperties cfg =
                properties.getProviders().getJutsu().getDriftProbe();
        log.info(
                "JutsuDriftScheduledProbe enabled — interval={}min, canonical slug={}, initial"
                        + " delay={}s",
                cfg.getIntervalMinutes(),
                cfg.getCanonicalSlug(),
                cfg.getInitialDelaySeconds());
    }

    /**
     * Spring's {@code @Scheduled} reads {@code initialDelay} / {@code fixedRate} as fixed numbers
     * at parse time, but the values come from {@link OrinunoProperties}; using {@code
     * fixedRateString = "${...}"} resolves them at @Scheduled-init time which is enough for our
     * purposes (changing the property requires a restart, same as every other scheduled job).
     */
    @Scheduled(
            fixedRateString =
                    "#{${orinuno.providers.jutsu.drift-probe.interval-minutes:360} * 60 * 1000}",
            initialDelayString =
                    "#{${orinuno.providers.jutsu.drift-probe.initial-delay-seconds:60} * 1000}")
    public void runProbe() {
        long iteration = runCount.incrementAndGet();
        OrinunoProperties.JutsuProperties.DriftProbeProperties cfg =
                properties.getProviders().getJutsu().getDriftProbe();
        String slug = cfg.getCanonicalSlug();
        log.debug("JutsuDriftScheduledProbe run #{} starting (canonical slug={})", iteration, slug);
        try {
            // The probe is intentionally tiny: 3 calls under the same 1-RPS bucket as the rest of
            // the SDK. Anything more is wasteful (we just need any drift to land in the window).
            jutsuClient.browseCatalog(1).block(Duration.ofSeconds(45));
            jutsuClient.getAnimeInfo(slug).block(Duration.ofSeconds(45));
            jutsuClient.getLatestNoticeFeed().block(Duration.ofSeconds(45));

            JutsuDriftSnapshot snap = jutsuClient.getDriftSnapshot();
            log.info(
                    "JutsuDriftScheduledProbe run #{} ok — health={} lifetimeEvents={}"
                            + " eventsInWindow={}",
                    iteration,
                    snap.health(),
                    snap.lifetimeEvents(),
                    snap.eventsInWindow());
        } catch (RuntimeException ex) {
            failureCount.incrementAndGet();
            log.warn("JutsuDriftScheduledProbe run #{} failed — {}", iteration, ex.toString());
        }
    }

    /** Visible for tests / management endpoints. */
    public long runCount() {
        return runCount.get();
    }

    /** Visible for tests / management endpoints. */
    public long failureCount() {
        return failureCount.get();
    }
}
