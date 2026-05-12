/*
 * ThrottledProgressReporter — ADR 0021 §D1a.
 *
 * Throttled writer that batches progress updates and forwards them to
 * ParseRequestRepository. Phase transitions always flush immediately;
 * final flush() guarantees the latest state is persisted regardless of
 * throttling. Ported verbatim from orinuno-app/.../requestlog/.
 */
package com.orinuno.source.kodik.service.requestlog;

import com.orinuno.source.kodik.model.ParseRequestPhase;
import com.orinuno.source.kodik.repository.ParseRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThrottledProgressReporter implements ProgressReporter {

    private final ParseRequestRepository repository;
    private final long requestId;
    private final Duration flushInterval;
    private final Clock clock;

    private int latestDecoded = 0;
    private int latestTotal = 0;
    private boolean dirty = false;
    private Instant lastFlushAt;

    public ThrottledProgressReporter(
            ParseRequestRepository repository,
            long requestId,
            Duration flushInterval,
            Clock clock) {
        this.repository = repository;
        this.requestId = requestId;
        this.flushInterval = flushInterval;
        this.clock = clock;
        this.lastFlushAt = clock.instant();
    }

    @Override
    public synchronized void update(int decoded, int total) {
        latestDecoded = decoded;
        latestTotal = total;
        dirty = true;

        Instant now = clock.instant();
        if (Duration.between(lastFlushAt, now).compareTo(flushInterval) >= 0) {
            flush();
        }
    }

    @Override
    public synchronized void phaseTransition(ParseRequestPhase phase) {
        LocalDateTime heartbeat = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        try {
            repository.updatePhase(requestId, phase, heartbeat);
        } catch (Exception e) {
            log.warn("Failed to record phase {} for request_id={}", phase, requestId, e);
        }
        if (dirty) {
            flush();
        }
    }

    public synchronized void flush() {
        if (!dirty) return;
        LocalDateTime heartbeat = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        try {
            repository.updateProgress(requestId, latestDecoded, latestTotal, heartbeat);
            lastFlushAt = clock.instant();
            dirty = false;
        } catch (Exception e) {
            log.warn(
                    "Failed to flush progress {}/{} for request_id={}",
                    latestDecoded,
                    latestTotal,
                    requestId,
                    e);
        }
    }
}
