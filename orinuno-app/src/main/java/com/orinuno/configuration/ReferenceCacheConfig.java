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
 * Caffeine-backed cache for Kodik reference endpoints + the public Kodik calendar dump (IDEA-AP-5).
 * The {@code orinuno.cache.reference.enabled} toggle replaces the manager with {@link
 * NoOpCacheManager}, turning {@link org.springframework.cache.annotation.Cacheable @Cacheable} into
 * a pass-through across the whole application.
 *
 * <p>Reference dictionaries and the calendar dump live in the same {@link CaffeineCacheManager} but
 * use different TTLs — reference data is stable for hours, the calendar dump turns over every few
 * minutes — so the calendar cache is registered as a custom Caffeine instance via {@link
 * CaffeineCacheManager#registerCustomCache}.
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
    public static final String CALENDAR = "kodik-calendar";

    @Bean
    public CacheManager cacheManager(OrinunoProperties properties) {
        OrinunoProperties.ReferenceCacheProperties cfg = properties.getCache().getReference();
        if (!cfg.isEnabled()) {
            log.info("Kodik reference cache disabled — serving every request from upstream");
            return new NoOpCacheManager();
        }
        long calendarTtl = properties.getCalendar().getCacheTtlSeconds();
        log.info(
                "Kodik cache enabled — reference TTL={}s, calendar TTL={}s",
                cfg.getTtlSeconds(),
                calendarTtl);
        CaffeineCacheManager manager =
                new CaffeineCacheManager(TRANSLATIONS, GENRES, COUNTRIES, YEARS, QUALITIES);
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(cfg.getTtlSeconds()))
                        .maximumSize(32));
        manager.registerCustomCache(
                CALENDAR,
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(calendarTtl))
                        .maximumSize(8)
                        .build());
        return manager;
    }
}
