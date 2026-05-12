/*
 * SourceEventDecodedController — ADR 0021 §B2-decoded.
 *
 * Push-style channel for SourceCatalogEvent.VariantDecoded events. Decoder
 * owners (today: orinuno-app's ParserService for Kodik; future per-source
 * services for Aniboom / Sibnet) POST a decoded URL here after a successful
 * decode and meter routes it through CatalogSinkEventEmitter into
 * episode_video.
 *
 * Why push (not poll)? The other source-event channels (TitleObserved /
 * MovieDiscovered / …) use the watermarked poll endpoint on each per-source
 * service. For decoded URLs, the producer is orinuno-app which has no
 * polling infrastructure today, and the events are not durable on the
 * producer side — we want fire-and-forget. A future B2-decoded-outbox patch
 * may switch this to a poll-and-watermark shape; the JSON contract stays
 * the same (sealed SourceCatalogEvent), only the transport changes.
 *
 * Surface deliberately accepts the full sealed family rather than just
 * VariantDecoded — non-decoded variants are silently routed too, so a
 * producer that already has a SourceCatalogEvent in hand can POST without
 * caring about which variant it is. This matches the existing
 * /api/v1/source-events/ready polled endpoint's body shape.
 */
package com.orinuno.meter.catalog.ingestion;

import com.orinuno.contract.source.SourceCatalogEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/source-events")
@RequiredArgsConstructor
public class SourceEventDecodedController {

    private final CatalogSinkEventEmitter emitter;

    /**
     * Accept a batch of source-catalog events. Each event is routed through {@link
     * CatalogSinkEventEmitter#emit(SourceCatalogEvent)} which already swallows per-event errors —
     * so a malformed entry can't take down the whole batch. Returns {@code 202 Accepted}: meter has
     * recorded what it could; the producer should treat this as fire-and-forget and not retry on
     * non-2xx (the canonical write path is the polled {@code /api/v1/source-events/ready} on each
     * per-source service).
     */
    @PostMapping("/decoded")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@RequestBody List<SourceCatalogEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        log.debug("source-events/decoded: ingesting batch of {} event(s)", events.size());
        for (SourceCatalogEvent event : events) {
            emitter.emit(event);
        }
    }
}
