package com.orinuno.configuration;

import com.kodik.drift.DriftSamplingProperties;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * orinuno-app gateway properties.
 *
 * <p>ADR 0021 §E2 stage 3b — every L1 / decoder / proxy / storage / playwright / calendar / dumps
 * subtree retired with the slice that consumed it. orinuno-app is now a thin gateway + cross-source
 * orchestrator; the few configuration knobs that remain here cover the cross-cutting concerns the
 * gateway still owns:
 *
 * <ul>
 *   <li>{@link ParseProperties} — inbound rate limit for {@code POST /api/v1/parse/requests}.
 *   <li>{@link SecurityProperties} — gateway API key.
 *   <li>{@link CorsProperties} — allowed origins for the demo UI / browser clients.
 *   <li>{@link ProvidersProperties} — JutSu provider auth + drift probe.
 *   <li>{@link DriftSamplingProperties} — global drift detector knob shared across SDKs.
 * </ul>
 *
 * <p>Per-source SDK configs live under {@code kodik.sdk.*} (kodik-sdk-spring-boot-starter) and
 * {@code orinuno.source-kodik.*} (orinuno-source-kodik). The orinuno.catalog-read.* keys are
 * consumed by {@link com.orinuno.catalog.readonly.CatalogReadDataSourceConfiguration}.
 */
@Data
@ConfigurationProperties(prefix = "orinuno")
public class OrinunoProperties {

    private ParseProperties parse = new ParseProperties();
    private SecurityProperties security = new SecurityProperties();
    private CorsProperties cors = new CorsProperties();
    private DriftSamplingProperties drift = new DriftSamplingProperties();
    private ProvidersProperties providers = new ProvidersProperties();

    @Data
    public static class ParseProperties {
        private int rateLimitPerMinute = 30;
        private InboundRateLimitProperties inboundRateLimit = new InboundRateLimitProperties();
    }

    /**
     * Inbound rate limit applied to {@code POST /api/v1/parse/requests}. Per-consumer
     * (X-Created-By) token bucket. Surfaced as the {@code orinuno_inbound_throttle_total}
     * Prometheus counter and the integration health endpoint. See operations/downstream
     * consumer-integration.
     */
    @Data
    public static class InboundRateLimitProperties {
        private boolean enabled = true;
        private int requestsPerMinute = 60;
    }

    @Data
    public static class SecurityProperties {
        private String apiKey = "";
    }

    @Data
    public static class CorsProperties {
        private List<String> allowedOrigins = List.of("*");
    }

    /**
     * Settings for the alternative video providers we decode (Sibnet, Aniboom, JutSu). Most are
     * stateless decoders so they live without configuration; JutSu is special because real CDN URLs
     * are gated behind a {@code Jutsu+} subscription, so we ship a per-provider auth + rate-limit
     * block. Keeping providers under one prefix means future Aniboom/Sibnet auth (if they ever
     * introduce it) can hang off the same parent without renaming env vars.
     */
    @Data
    public static class ProvidersProperties {
        private JutsuProperties jutsu = new JutsuProperties();
    }

    /**
     * JutSu (jut.su) provider — DataLife Engine login + sticky cookie session + outbound rate
     * limit. See {@code docs/quirks-and-hacks.md} → "JutSu premium gating leaks &lt;source&gt; tags
     * with placeholder URLs" for the why.
     *
     * <ul>
     *   <li>{@code username/password} — DLE form fields. Empty by default; when blank the decoder
     *       runs in anonymous mode and returns {@code JUTSU_PREMIUM_REQUIRED} for gated episodes.
     *       NEVER commit real values; populate via {@code JUTSU_USERNAME / JUTSU_PASSWORD} env vars
     *       only.
     *   <li>{@code rate-limit-rps} — outbound requests per second to {@code jut.su}, hard-capped to
     *       protect the account from being flagged for API abuse. Default {@code 1.0} matches what
     *       a single human browsing tab generates. Floors at {@code 0.1} (1 req / 10s); raise only
     *       if you have a separate rate-limit agreement with jut.su.
     *   <li>{@code session-ttl-minutes} — how long the cached cookie jar is treated as fresh before
     *       we proactively re-login. The DLE cookies are valid for ~50 days (we observed {@code
     *       Max-Age} ~52 weeks on {@code dle_password}), but we re-login much earlier so a silent
     *       password rotation or account ban surfaces while operators are still on shift.
     *   <li>{@code base-url} — kept configurable so a staging mirror or HAR-replay server can be
     *       swapped in for tests. Production should never override this.
     * </ul>
     */
    @Data
    public static class JutsuProperties {
        private String baseUrl = "https://jut.su";
        private String username = "";
        private String password = "";
        private double rateLimitRps = 1.0;
        private long sessionTtlMinutes = 240;
        private int loginTimeoutSeconds = 15;

        /**
         * Drift canary probe knobs. The probe periodically calls a small fixed set of jut.su
         * endpoints (latest notice feed, OnePunch Man anime info, page 1 of the unfiltered
         * catalog). Drift signals observed during these calls are aggregated into the SDK drift
         * detector and read by {@code MultiSourceRanker} to decide whether to demote jut.su.
         *
         * <p>Disabled by default in tests; enabled in production via {@code
         * orinuno.providers.jutsu.drift-probe.enabled=true}.
         */
        private DriftProbeProperties driftProbe = new DriftProbeProperties();

        public boolean hasCredentials() {
            return username != null
                    && !username.isBlank()
                    && password != null
                    && !password.isBlank();
        }

        @Data
        public static class DriftProbeProperties {
            private boolean enabled = false;

            /** Minutes between probe runs. Default 6 hours = 360 minutes. */
            private long intervalMinutes = 360;

            /** Initial delay before the first probe run, in seconds. */
            private long initialDelaySeconds = 60;

            /**
             * Anime slug used for the info-page canary probe. Must be a slug that exists on the
             * site for the lifetime of the probe; defaults to OnePunch Man because we have a
             * captured fixture for it.
             */
            private String canonicalSlug = "onepuunchman";
        }
    }
}
