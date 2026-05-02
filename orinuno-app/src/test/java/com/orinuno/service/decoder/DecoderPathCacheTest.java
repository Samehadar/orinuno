package com.orinuno.service.decoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.model.KodikDecoderPathCacheEntry;
import com.orinuno.repository.KodikDecoderPathCacheRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * DECODE-2 — covers the persistent layer of {@link DecoderPathCache}: hydration on startup, upsert
 * on put, and graceful absorption of database failures.
 */
class DecoderPathCacheTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @Test
    @DisplayName("hydrateFromDatabase loads existing rows into the in-memory map (lower-cased)")
    void hydratesFromRepository() {
        KodikDecoderPathCacheRepository repo = mock(KodikDecoderPathCacheRepository.class);
        when(repo.findAll())
                .thenReturn(
                        List.of(
                                entry("kodikplayer.com", "/ftor"),
                                entry("KODIK.CC", "/kor"),
                                entry("kodikv.cc", "/gvi")));

        DecoderPathCache cache = new DecoderPathCache(providerOf(repo));
        cache.hydrateFromDatabase();

        assertThat(cache.snapshot())
                .containsEntry("kodikplayer.com", "/ftor")
                .containsEntry("kodik.cc", "/kor")
                .containsEntry("kodikv.cc", "/gvi");
    }

    @Test
    @DisplayName("hydrateFromDatabase skips rows with null fields or non-/ paths")
    void hydrationSkipsInvalidRows() {
        KodikDecoderPathCacheRepository repo = mock(KodikDecoderPathCacheRepository.class);
        when(repo.findAll())
                .thenReturn(
                        List.of(
                                entry("kodik.cc", null),
                                entry(null, "/ftor"),
                                entry("kodik.cc", "noslash"),
                                entry("kodikplayer.com", "/ftor")));

        DecoderPathCache cache = new DecoderPathCache(providerOf(repo));
        cache.hydrateFromDatabase();

        assertThat(cache.snapshot()).hasSize(1).containsEntry("kodikplayer.com", "/ftor");
    }

    @Test
    @DisplayName("hydrateFromDatabase swallows repository exceptions and starts empty")
    void hydrationAbsorbsRepoFailure() {
        KodikDecoderPathCacheRepository repo = mock(KodikDecoderPathCacheRepository.class);
        when(repo.findAll()).thenThrow(new RuntimeException("DB unreachable"));

        DecoderPathCache cache = new DecoderPathCache(providerOf(repo));
        cache.hydrateFromDatabase();

        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("hydrateFromDatabase is a no-op when no repository is wired")
    void hydrationWithoutRepoIsNoOp() {
        DecoderPathCache cache = new DecoderPathCache(providerOf(null));
        cache.hydrateFromDatabase();

        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("put fires off async upsertCachedPath when repo is available")
    void putPersistsAsync() {
        KodikDecoderPathCacheRepository repo = mock(KodikDecoderPathCacheRepository.class);
        DecoderPathCache cache = new DecoderPathCache(providerOf(repo));

        cache.put("KODIK.cc", "/ftor");

        verify(repo, timeout(2_000).times(1))
                .upsertCachedPath(eq("kodik.cc"), eq("/ftor"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("put silently absorbs upsert failures (decoder must never block on DB)")
    void putAbsorbsRepoFailure() {
        KodikDecoderPathCacheRepository repo = mock(KodikDecoderPathCacheRepository.class);
        doThrow(new RuntimeException("write failed"))
                .when(repo)
                .upsertCachedPath(any(), any(), any());

        DecoderPathCache cache = new DecoderPathCache(providerOf(repo));
        cache.put("kodik.cc", "/ftor");

        verify(repo, timeout(2_000).times(1))
                .upsertCachedPath(eq("kodik.cc"), eq("/ftor"), any(LocalDateTime.class));
        assertThat(cache.snapshot()).containsEntry("kodik.cc", "/ftor");
    }

    @Test
    @DisplayName("put does not call repo when inputs are invalid")
    void putRejectsInvalidInputsBeforePersisting() {
        KodikDecoderPathCacheRepository repo = mock(KodikDecoderPathCacheRepository.class);
        DecoderPathCache cache = new DecoderPathCache(providerOf(repo));

        cache.put(null, "/ftor");
        cache.put("", "/ftor");
        cache.put("kodik.cc", null);
        cache.put("kodik.cc", "noslash");

        verify(repo, never()).upsertCachedPath(any(), any(), any());
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("put without repo provider just memoises in memory")
    void putWithoutRepoIsInMemoryOnly() {
        DecoderPathCache cache = new DecoderPathCache(providerOf(null));

        cache.put("kodik.cc", "/ftor");

        assertThat(cache.get("kodik.cc")).contains("/ftor");
    }

    @Test
    @DisplayName("put repeatedly persists each call (so hit_count keeps incrementing)")
    void putPersistsEachCall() {
        KodikDecoderPathCacheRepository repo = mock(KodikDecoderPathCacheRepository.class);
        DecoderPathCache cache = new DecoderPathCache(providerOf(repo));

        cache.put("kodik.cc", "/ftor");
        cache.put("kodik.cc", "/ftor");
        cache.put("kodik.cc", "/ftor");

        verify(repo, timeout(2_000).times(3))
                .upsertCachedPath(eq("kodik.cc"), eq("/ftor"), any(LocalDateTime.class));
    }

    private static KodikDecoderPathCacheEntry entry(String netloc, String path) {
        return KodikDecoderPathCacheEntry.builder()
                .netloc(netloc)
                .videoInfoPath(path)
                .firstSeenAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .hitCount(1L)
                .build();
    }
}
