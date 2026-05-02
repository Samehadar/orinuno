package com.orinuno.service.dumps;

import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.configuration.OrinunoProperties.DumpsProperties.DumpEntry;
import com.orinuno.mapper.EntityFactory;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.service.ContentService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DUMP-2 — Bootstrap an empty (or partially-populated) database from one of the public Kodik dumps.
 * Streams the upstream JSON array via {@link KodikDumpStreamingReader} so even the 175 MB {@code
 * serials.json} only consumes ~1 MB of resident memory during ingestion.
 *
 * <p>Each parsed {@link KodikSearchResponse.Result} is converted via {@link EntityFactory} (the
 * same code path used by {@code ParserService} for live API responses) and then upserted via {@link
 * ContentService#findOrCreateContent(KodikContent)} + {@link
 * ContentService#saveVariants(java.util.List)}. This means the bootstrap path automatically
 * benefits from existing idempotency rules (kinopoisk_id-keyed lookup with title+year fallback,
 * COALESCE upsert that never overwrites a valid mp4_link).
 *
 * <p>The downloads themselves are gated behind {@code orinuno.dumps.download-body=true} —
 * accidentally hammering Kodik's CDN with multi-GB GETs is exactly the failure mode this gate
 * exists to prevent. Operators bootstrap by toggling the flag, calling {@link #bootstrap(String)},
 * then turning it back off.
 *
 * <p>This service does NOT register any scheduled job; it's invoked imperatively from an admin tool
 * or a one-off CLI / startup hook. {@link KodikDumpService} is the watcher that runs on a cadence;
 * this is the heavyweight one-shot.
 */
@Slf4j
@Service
public class KodikDumpBootstrapService {

    private final OrinunoProperties properties;
    private final ContentService contentService;
    private final RotatingUserAgentProvider userAgentProvider;
    private final HttpClient httpClient;

    public KodikDumpBootstrapService(
            OrinunoProperties properties,
            ContentService contentService,
            RotatingUserAgentProvider userAgentProvider) {
        this.properties = properties;
        this.contentService = contentService;
        this.userAgentProvider = userAgentProvider;
        this.httpClient =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15))
                        .build();
    }

    /**
     * Download and ingest a named dump (one of {@code calendar} / {@code serials} / {@code films}).
     * Blocks the calling thread until the entire stream is processed.
     *
     * @throws IllegalStateException if {@code orinuno.dumps.download-body=false} (the safety gate)
     *     or the dump name is unknown.
     * @throws IOException on transport / parser failure (per-element failures are absorbed and
     *     reported in the {@link BootstrapResult#skipped} counter).
     */
    public BootstrapResult bootstrap(String dumpName) throws IOException, InterruptedException {
        ensureDownloadEnabled();
        DumpEntry entry = resolveEntry(dumpName);
        String url = buildUrl(entry);
        log.info(
                "🌱 DUMP-2 bootstrap starting: name={} url={} (this is a heavy download — ~80 MB+)",
                dumpName,
                url);

        HttpResponse<InputStream> response = openStream(url);
        if (response.statusCode() / 100 != 2) {
            throw new IOException(
                    "Unexpected HTTP status " + response.statusCode() + " when downloading " + url);
        }

        AtomicLong inserted = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        try (InputStream body = response.body()) {
            KodikDumpStreamingReader.StreamingResult parsed =
                    KodikDumpStreamingReader.readArray(
                            body,
                            result -> {
                                try {
                                    ingestOne(result);
                                    inserted.incrementAndGet();
                                } catch (RuntimeException ex) {
                                    rejected.incrementAndGet();
                                    log.warn(
                                            "DUMP-2: failed to upsert dump entry id={}: {}",
                                            result.getId(),
                                            ex.toString());
                                }
                            });
            BootstrapResult outcome =
                    new BootstrapResult(
                            dumpName,
                            url,
                            parsed.processed(),
                            parsed.skipped() + rejected.get(),
                            inserted.get());
            log.info(
                    "✅ DUMP-2 bootstrap done: name={} parsed={} skipped={} inserted={}",
                    dumpName,
                    outcome.parsed(),
                    outcome.skipped(),
                    outcome.inserted());
            return outcome;
        }
    }

    /** Test-only entry point: ingest from any {@link InputStream}. */
    public BootstrapResult bootstrapFromStream(String dumpName, InputStream stream)
            throws IOException {
        AtomicLong inserted = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        KodikDumpStreamingReader.StreamingResult parsed =
                KodikDumpStreamingReader.readArray(
                        stream,
                        result -> {
                            try {
                                ingestOne(result);
                                inserted.incrementAndGet();
                            } catch (RuntimeException ex) {
                                rejected.incrementAndGet();
                                log.warn(
                                        "DUMP-2: failed to upsert dump entry id={}: {}",
                                        result.getId(),
                                        ex.toString());
                            }
                        });
        return new BootstrapResult(
                dumpName,
                null,
                parsed.processed(),
                parsed.skipped() + rejected.get(),
                inserted.get());
    }

    private void ingestOne(KodikSearchResponse.Result result) {
        KodikContent draft = EntityFactory.createContent(result);
        KodikContent persisted = contentService.findOrCreateContent(draft);
        List<KodikEpisodeVariant> variants =
                EntityFactory.createVariants(persisted.getId(), result);
        contentService.saveVariants(variants);
    }

    private void ensureDownloadEnabled() {
        if (!properties.getDumps().isDownloadBody()) {
            throw new IllegalStateException(
                    "Dump body downloads are disabled. Set orinuno.dumps.download-body=true to"
                            + " allow bootstrap (this gates against multi-GB accidental fetches).");
        }
    }

    private DumpEntry resolveEntry(String dumpName) {
        return switch (Optional.ofNullable(dumpName).orElse("").toLowerCase()) {
            case "calendar" -> properties.getDumps().getCalendar();
            case "serials" -> properties.getDumps().getSerials();
            case "films" -> properties.getDumps().getFilms();
            default ->
                    throw new IllegalStateException(
                            "Unknown dump name: '"
                                    + dumpName
                                    + "' (expected calendar/serials/films)");
        };
    }

    String buildUrl(DumpEntry entry) {
        String base = properties.getDumps().getBaseUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + entry.getPath();
    }

    private HttpResponse<InputStream> openStream(String url)
            throws IOException, InterruptedException {
        long timeoutSeconds = Math.max(60, properties.getDumps().getRequestTimeoutSeconds() * 4);
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header(
                                "User-Agent",
                                RotatingUserAgentProvider.orinunoBot("dump-bootstrap"))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** Outcome of one bootstrap call — stable shape for admin tooling and tests. */
    public record BootstrapResult(
            String dumpName, String dumpUrl, long parsed, long skipped, long inserted) {}
}
