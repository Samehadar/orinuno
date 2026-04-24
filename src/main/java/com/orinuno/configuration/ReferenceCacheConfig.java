package com.orinuno.configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed cache for Kodik reference endpoints. The {@code orinuno.cache.reference.enabled}
 * toggle replaces the manager with {@link NoOpCacheManager}, turning {@link
 * org.springframework.cache.annotation.Cacheable @Cacheable} into a pass-through. This lets
 * operators force fresh reads for cases where reference data is expected to move (ongoings,
 * novelties) without redeploying with a different cache library.
 */
@Slf4j
@Configuration
@EnableCaching
public class ReferenceCacheConfig {

    public static final String TRANSLATIONS = "kodik-translations";
    public static final String GENRES = "kodik-genres";
    public static final String COUNTRIES = "kodik-countries";
    public static final String YEARS = "kodik-years";
    public static final String QUALITIES = "kodik-qualities";

    @Bean
    public CacheManager cacheManager(OrinunoProperties properties) {
        OrinunoProperties.ReferenceCacheProperties cfg = properties.getCache().getReference();
        if (!cfg.isEnabled()) {
            log.info("Kodik reference cache disabled — serving every request from upstream");
            return new NoOpCacheManager();
        }
        log.info("Kodik reference cache enabled — Caffeine TTL={}s", cfg.getTtlSeconds());
        CaffeineCacheManager manager =
                new CaffeineCacheManager(TRANSLATIONS, GENRES, COUNTRIES, YEARS, QUALITIES);
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(cfg.getTtlSeconds()))
                        .maximumSize(32));
        return manager;
    }
}
