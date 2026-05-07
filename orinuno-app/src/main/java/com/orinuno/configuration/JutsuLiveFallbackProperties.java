package com.orinuno.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knobs for {@link com.orinuno.jutsu.fallback.JutsuLiveFallbackService} (ADR 0016 P1a). Default
 * {@link #enabled} is {@code false} — operators must explicitly opt in via {@code
 * JUTSU_LIVE_FALLBACK_ENABLED=true} after they've confirmed rate-limit, negative-cache and metrics
 * are wired. The dev profile flips it on in {@code application.yml}.
 *
 * @param enabled global kill-switch. When {@code false}, cache misses return 404 directly without
 *     touching upstream.
 * @param rateLimit per-consumer rate limit for live-fallback requests. {@code requestsPerSecond =
 *     0.2} ⇒ 1 request / 5s.
 * @param negativeCache Caffeine-backed negative cache (slug → 404). 24h TTL closes the
 *     "iterate-non-existent-slugs" attack surface.
 * @param buckets bookkeeping for the per-consumer Bucket4j map. {@code expireAfterAccessHours}
 *     bounds memory growth in front of public traffic; {@code maxSize} caps the worst case.
 */
@ConfigurationProperties(prefix = "orinuno.jutsu.live-fallback")
public record JutsuLiveFallbackProperties(
        boolean enabled, RateLimit rateLimit, NegativeCache negativeCache, Buckets buckets) {

    public JutsuLiveFallbackProperties() {
        this(false, new RateLimit(), new NegativeCache(), new Buckets());
    }

    @ConstructorBinding
    public JutsuLiveFallbackProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue RateLimit rateLimit,
            @DefaultValue NegativeCache negativeCache,
            @DefaultValue Buckets buckets) {
        this.enabled = enabled;
        this.rateLimit = rateLimit != null ? rateLimit : new RateLimit();
        this.negativeCache = negativeCache != null ? negativeCache : new NegativeCache();
        this.buckets = buckets != null ? buckets : new Buckets();
    }

    public record RateLimit(double requestsPerSecond) {
        public RateLimit() {
            this(0.2);
        }

        @ConstructorBinding
        public RateLimit(@DefaultValue("0.2") double requestsPerSecond) {
            this.requestsPerSecond = requestsPerSecond;
        }

        public double effectiveRequestsPerSecond() {
            return Math.max(0.001, requestsPerSecond);
        }
    }

    public record NegativeCache(long ttlHours) {
        public NegativeCache() {
            this(24);
        }

        @ConstructorBinding
        public NegativeCache(@DefaultValue("24") long ttlHours) {
            this.ttlHours = ttlHours;
        }

        public long effectiveTtlHours() {
            return Math.max(1L, ttlHours);
        }
    }

    public record Buckets(long expireAfterAccessHours, long maxSize) {
        public Buckets() {
            this(1L, 50_000L);
        }

        @ConstructorBinding
        public Buckets(
                @DefaultValue("1") long expireAfterAccessHours,
                @DefaultValue("50000") long maxSize) {
            this.expireAfterAccessHours = expireAfterAccessHours;
            this.maxSize = maxSize;
        }

        public long effectiveExpireAfterAccessHours() {
            return Math.max(1L, expireAfterAccessHours);
        }

        public long effectiveMaxSize() {
            return Math.max(1_000L, maxSize);
        }
    }
}
