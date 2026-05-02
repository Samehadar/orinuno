package com.orinuno.service.dumps;

import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.configuration.OrinunoProperties.DumpsProperties.DumpEntry;
import com.orinuno.model.OrinunoDumpState;
import com.orinuno.repository.DumpStateRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * DUMP-1 — Polls the public Kodik dump endpoints ({@code
 * https://dumps.kodikres.com/{calendar,serials,films}.json}) and persists per-dump state into
 * {@code orinuno_dump_state} so the health endpoint and downstream consumers can answer:
 *
 * <ul>
 *   <li>"When did Kodik last publish a fresh {@code films.json}?" (→ {@code lastChangedAt})
 *   <li>"When did orinuno last successfully reach the dump?" (→ {@code lastCheckedAt})
 *   <li>"Is the dump currently failing?" (→ {@code lastStatus} + {@code consecutiveFailures})
 * </ul>
 *
 * <p>Default mode is HEAD-only — bodies are huge (~175 MB serials / ~82 MB films) and we only need
 * to compare ETag / Last-Modified / Content-Length. Body download is gated behind {@code
 * orinuno.dumps.download-body=true} (DUMP-2 will use it for bootstrap).
 *
 * <p>The service is idempotent and safe to run on multiple replicas: the state row is keyed by
 * {@code dump_name} (UNIQUE) and updates are an atomic {@code ON DUPLICATE KEY UPDATE}.
 *
 * <p>This class is itself stateless; the schedule lives in {@link DumpScheduler}.
 */
@Slf4j
@Service
public class KodikDumpService {

    private final OrinunoProperties properties;
    private final DumpStateRepository repository;
    private final RotatingUserAgentProvider userAgentProvider;
    private final WebClient webClient;

    public KodikDumpService(
            OrinunoProperties properties,
            DumpStateRepository repository,
            RotatingUserAgentProvider userAgentProvider) {
        this.properties = properties;
        this.repository = repository;
        this.userAgentProvider = userAgentProvider;
        this.webClient = buildWebClient(properties);
    }

    /**
     * Polls every enabled dump entry once. Designed to be invoked by {@link DumpScheduler} on a
     * fixed cadence; safe to invoke directly from tests / health endpoints / admin tooling.
     *
     * <p>Per-dump failures do not propagate: each entry is wrapped, logged and persisted with the
     * error so that one stuck endpoint does not stop us from recording state for the others.
     *
     * @return list of poll results in the same order the entries were processed.
     */
    public List<DumpPollResult> pollAll() {
        if (!properties.getDumps().isEnabled()) {
            log.debug("Dump polling disabled (orinuno.dumps.enabled=false)");
            return List.of();
        }
        List<DumpPollResult> results = new ArrayList<>();
        for (Map.Entry<String, DumpEntry> e : enabledEntries().entrySet()) {
            try {
                results.add(pollSingle(e.getKey(), e.getValue()));
            } catch (RuntimeException ex) {
                log.warn("Dump poll [{}] failed: {}", e.getKey(), ex.toString());
                results.add(persistFailure(e.getKey(), e.getValue(), ex));
            }
        }
        return results;
    }

    /** Polls a single named dump and persists the result. Public so admin endpoints can call it. */
    public DumpPollResult pollSingle(String name, DumpEntry entry) {
        String url = buildUrl(entry);
        log.debug("Polling Kodik dump [{}] {}", name, url);
        Optional<OrinunoDumpState> previous = repository.findByName(name);
        try {
            HeadResponse head = head(url);
            return persistSuccess(name, url, head, previous.orElse(null));
        } catch (RuntimeException ex) {
            return persistFailure(name, entry, ex);
        }
    }

    /** Snapshot of the latest state for every tracked dump. Used by the health endpoint. */
    public List<OrinunoDumpState> snapshot() {
        return repository.findAll();
    }

    // -- internals ------------------------------------------------------------

    private Map<String, DumpEntry> enabledEntries() {
        Map<String, DumpEntry> all = new LinkedHashMap<>();
        all.put("calendar", properties.getDumps().getCalendar());
        all.put("serials", properties.getDumps().getSerials());
        all.put("films", properties.getDumps().getFilms());
        Map<String, DumpEntry> enabled = new LinkedHashMap<>();
        for (Map.Entry<String, DumpEntry> e : all.entrySet()) {
            if (e.getValue() != null && e.getValue().isEnabled())
                enabled.put(e.getKey(), e.getValue());
        }
        return enabled;
    }

    String buildUrl(DumpEntry entry) {
        String base = trimTrailingSlash(properties.getDumps().getBaseUrl());
        return base + "/" + entry.getPath();
    }

    private static String trimTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private HeadResponse head(String url) {
        long timeoutSeconds = Math.max(1, properties.getDumps().getRequestTimeoutSeconds());
        return webClient
                .method(HttpMethod.HEAD)
                .uri(url)
                .header(
                        HttpHeaders.USER_AGENT,
                        RotatingUserAgentProvider.orinunoBot("dump-watcher"))
                .header(HttpHeaders.ACCEPT, "*/*")
                .exchangeToMono(
                        response -> {
                            HttpHeaders headers = response.headers().asHttpHeaders();
                            HeadResponse hr =
                                    new HeadResponse(
                                            response.statusCode().value(),
                                            headers.getETag(),
                                            headers.getFirst(HttpHeaders.LAST_MODIFIED),
                                            headers.getContentLength() < 0
                                                    ? null
                                                    : headers.getContentLength());
                            return response.releaseBody().thenReturn(hr);
                        })
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }

    private DumpPollResult persistSuccess(
            String name, String url, HeadResponse head, OrinunoDumpState previous) {
        LocalDateTime now = LocalDateTime.now();
        boolean changed = !sameAs(previous, head);
        LocalDateTime changedAt = changed ? now : null;
        int prevFailures =
                previous == null
                        ? 0
                        : Optional.ofNullable(previous.getConsecutiveFailures()).orElse(0);
        int failures = head.status() / 100 == 2 ? 0 : prevFailures + 1;
        repository.upsertCheckResult(
                name,
                url,
                now,
                changedAt,
                head.status(),
                head.status() / 100 == 2 ? null : "HTTP " + head.status(),
                head.etag(),
                head.lastModified(),
                head.contentLength(),
                failures);
        if (changed && head.status() / 100 == 2) {
            log.info(
                    "Dump [{}] changed: status={} content-length={} etag={} last-modified={}",
                    name,
                    head.status(),
                    head.contentLength(),
                    head.etag(),
                    head.lastModified());
        }
        return new DumpPollResult(name, url, head.status(), changed, null);
    }

    private DumpPollResult persistFailure(String name, DumpEntry entry, RuntimeException ex) {
        String url = buildUrl(entry);
        OrinunoDumpState previous = repository.findByName(name).orElse(null);
        int prevFailures =
                previous == null
                        ? 0
                        : Optional.ofNullable(previous.getConsecutiveFailures()).orElse(0);
        repository.upsertCheckResult(
                name,
                url,
                LocalDateTime.now(),
                null,
                null,
                truncate(ex.toString(), 1024),
                null,
                null,
                null,
                prevFailures + 1);
        return new DumpPollResult(name, url, null, false, ex.toString());
    }

    private static boolean sameAs(OrinunoDumpState previous, HeadResponse head) {
        if (previous == null) return false;
        return Objects.equals(previous.getEtag(), head.etag())
                && Objects.equals(previous.getLastModifiedHeader(), head.lastModified())
                && Objects.equals(previous.getContentLength(), head.contentLength());
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static WebClient buildWebClient(OrinunoProperties properties) {
        long timeoutMs = Math.max(1, properties.getDumps().getRequestTimeoutSeconds()) * 1000L;
        HttpClient http =
                HttpClient.create()
                        .followRedirect(true)
                        .responseTimeout(Duration.ofMillis(timeoutMs));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
    }

    /** Single HEAD response distilled into the four fields we persist. */
    record HeadResponse(int status, String etag, String lastModified, Long contentLength) {}

    /** Outcome of one poll cycle for one dump — used by the health endpoint and tests. */
    public record DumpPollResult(
            String name, String url, Integer status, boolean changed, String error) {}
}
