/*
 * KodikCacheProperties — ADR 0021 §E2.
 *
 * Caffeine cache toggle + TTL for the Kodik reference endpoints +
 * calendar dump. Replaces OrinunoProperties.CacheProperties.reference
 * subtree. Prefix: orinuno.source-kodik.cache.reference.*.
 */
package com.orinuno.source.kodik.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno.source-kodik.cache.reference")
public class KodikCacheProperties {

    private boolean enabled = true;
    private long ttlSeconds = 21_600;
}
