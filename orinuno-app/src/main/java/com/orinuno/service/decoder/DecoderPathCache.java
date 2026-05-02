package com.orinuno.service.decoder;

import com.orinuno.model.KodikDecoderPathCacheEntry;
import com.orinuno.repository.KodikDecoderPathCacheRepository;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * DECODE-2 — persistent per-netloc decoder path cache.
 *
 * <p>The {@link com.orinuno.service.KodikVideoDecoderService} brute-force-discovers the video-info
 * POST path (one of {@code /ftor}, {@code /kor}, {@code /gvi}, {@code /seria}) by parsing the
 * player JS on first contact with each Kodik netloc, then memoises the result. DECODE-7 already
 * made this cache per-netloc, but the cache lived only in a JVM-local {@link ConcurrentHashMap}, so
 * a restart paid the discovery + brute-force cost again across all known netlocs ({@code
 * kodikplayer.com}, {@code kodik.cc}, {@code kodikv.cc}, plus future ones).
 *
 * <p>This bean adds the persistence layer: on construction it hydrates the in-memory map from the
 * {@code kodik_decoder_path_cache} table and on every {@link #put} it asynchronously upserts the
 * row. Persistence failures are absorbed (logged then swallowed) so the decoder never blocks on the
 * database — the in-memory map remains the source of truth for the current JVM.
 *
 * <p>Repository is provided via {@link ObjectProvider} so unit tests can construct this bean
 * without a Spring context (pass {@link ObjectProvider#getIfAvailable()} returning {@code null}).
 */
@Slf4j
@Component
public class DecoderPathCache {

    private final ObjectProvider<KodikDecoderPathCacheRepository> repositoryProvider;
    private final ConcurrentMap<String, String> map = new ConcurrentHashMap<>();

    public DecoderPathCache(ObjectProvider<KodikDecoderPathCacheRepository> repositoryProvider) {
        this.repositoryProvider = repositoryProvider;
    }

    /**
     * Hydrate the in-memory map from the database on application startup. Failures (no DB, missing
     * table, transient outage) are logged and swallowed — a fresh cold cache still works, it just
     * misses the optimisation for known netlocs.
     */
    @PostConstruct
    void hydrateFromDatabase() {
        KodikDecoderPathCacheRepository repository = repository();
        if (repository == null) {
            log.debug("DECODE-2: no decoder-path-cache repository wired, skipping hydration");
            return;
        }
        try {
            int loaded = 0;
            for (KodikDecoderPathCacheEntry entry : repository.findAll()) {
                if (entry.getNetloc() != null
                        && entry.getVideoInfoPath() != null
                        && entry.getVideoInfoPath().startsWith("/")) {
                    map.put(normalise(entry.getNetloc()), entry.getVideoInfoPath());
                    loaded++;
                }
            }
            log.info(
                    "DECODE-2: hydrated {} per-netloc decoder path cache entr{} from DB",
                    loaded,
                    loaded == 1 ? "y" : "ies");
        } catch (Exception ex) {
            log.warn(
                    "DECODE-2: failed to hydrate decoder path cache from DB ({}: {}), starting"
                            + " with empty cache",
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    /** Look up a previously-discovered POST path for the given netloc. */
    public Optional<String> get(String netloc) {
        if (netloc == null || netloc.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(normalise(netloc)));
    }

    /**
     * Memoise the discovered POST path for the netloc. Updates the in-memory map synchronously and
     * fires off a non-blocking DB upsert. Validates inputs so callers can pass through whatever
     * they extracted without pre-checking.
     */
    public void put(String netloc, String videoInfoPath) {
        if (netloc == null
                || netloc.isBlank()
                || videoInfoPath == null
                || !videoInfoPath.startsWith("/")) {
            return;
        }
        String key = normalise(netloc);
        map.put(key, videoInfoPath);
        persistAsync(key, videoInfoPath);
    }

    /** Snapshot of the in-memory cache. Visible for tests + the health endpoint. */
    public Map<String, String> snapshot() {
        return Map.copyOf(map);
    }

    /** Wipes the in-memory cache. Visible for tests; never called in production code. */
    public void clear() {
        map.clear();
    }

    private void persistAsync(String netloc, String path) {
        KodikDecoderPathCacheRepository repository = repository();
        if (repository == null) {
            return;
        }
        Mono.fromRunnable(
                        () -> {
                            try {
                                repository.upsertCachedPath(netloc, path, LocalDateTime.now());
                            } catch (Exception ex) {
                                log.warn(
                                        "DECODE-2: failed to persist decoder path for netloc {}:"
                                                + " {} ({})",
                                        netloc,
                                        ex.getClass().getSimpleName(),
                                        ex.getMessage());
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private KodikDecoderPathCacheRepository repository() {
        return repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
    }

    private static String normalise(String netloc) {
        return netloc.toLowerCase(Locale.ROOT);
    }
}
