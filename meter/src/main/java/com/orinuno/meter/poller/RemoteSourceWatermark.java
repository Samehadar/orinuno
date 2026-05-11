package com.orinuno.meter.poller;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent watermark for a {@code *RemoteEventPoller} (ADR 0018 Phase 2.11). One row per
 * per-source service we poll, keyed by the open-string {@code source_type} ("kodik", later "jutsu",
 * …) the same identifier that lives on {@link com.orinuno.contract.source.SourceIdentifier}.
 *
 * <p>{@link #lastFetchedAt} is the high-water mark on the consumed events' {@code
 * Provenance.fetchedAt} — the value to feed back as {@code updatedSince} on the next call to {@code
 * /api/v1/source-events/ready}. {@link #lastPolledAt} is bumped on every successful or failing
 * poll; {@link #lastError} captures the most recent transient error so the health endpoint can
 * surface it without grepping logs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteSourceWatermark {

    private String sourceType;
    private LocalDateTime lastFetchedAt;
    private LocalDateTime lastPolledAt;
    private int lastEventCount;
    private String lastError;
}
