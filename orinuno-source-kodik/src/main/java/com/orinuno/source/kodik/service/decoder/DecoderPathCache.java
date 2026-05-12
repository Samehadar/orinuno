/*
 * DecoderPathCache — ADR 0021 §D1b-prep.
 *
 * DECODE-2 persistent per-netloc decoder path cache. Ported from
 * orinuno-app/.../service/decoder/ — only the import paths for
 * KodikDecoderPathCacheEntry + KodikDecoderPathCacheRepository change
 * (source-kodik versions already exist).
 */
package com.orinuno.source.kodik.service.decoder;

import com.orinuno.source.kodik.model.KodikDecoderPathCacheEntry;
import com.orinuno.source.kodik.repository.KodikDecoderPathCacheRepository;
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

@Slf4j
@Component
public class DecoderPathCache {

    private final ObjectProvider<KodikDecoderPathCacheRepository> repositoryProvider;
    private final ConcurrentMap<String, String> map = new ConcurrentHashMap<>();

    public DecoderPathCache(ObjectProvider<KodikDecoderPathCacheRepository> repositoryProvider) {
        this.repositoryProvider = repositoryProvider;
    }

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

    public Optional<String> get(String netloc) {
        if (netloc == null || netloc.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(normalise(netloc)));
    }

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

    public Map<String, String> snapshot() {
        return Map.copyOf(map);
    }

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
