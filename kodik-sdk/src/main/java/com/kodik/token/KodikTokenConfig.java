/*
 * KodikTokenConfig — ADR 0018 Phase 1.4b.
 *
 * Spring-free config record consumed by the stateful token classes (Registry, RegistryFile,
 * Validator, Lifecycle, AutoDiscovery, Metrics). orinuno-app's KodikSdkConfiguration translates
 * OrinunoProperties.Kodik into this record so the SDK has no compile-time link to the app's
 * properties layout. Symmetric to the int-arg pattern in KodikApiRateLimiter (Phase 1.2c).
 */
package com.kodik.token;

import jakarta.annotation.Nullable;

/**
 * Configuration values for the Kodik token subsystem. Defaults mirror the values {@code
 * orinuno-app/application.yml} ships today; a builder is provided so test code and future {@code
 * kodik-sdk-spring-boot-starter} wiring can supply overrides without touching every constructor.
 *
 * @param tokenFile path to the file-backed token registry JSON (typically {@code
 *     ./data/kodik_tokens.json})
 * @param bootstrapToken nullable env-provided token; consumed only if {@link #bootstrapFromEnv()}
 *     is true
 * @param bootstrapFromEnv whether the registry should seed itself from {@code KODIK_TOKEN} when the
 *     file is missing or empty
 * @param autoDiscoveryEnabled whether KodikTokenAutoDiscovery is allowed to scrape legacy tokens
 *     from Kodik's public player bootstrap
 * @param validateOnStartup whether KodikTokenLifecycle runs a full registry validation pass on
 *     application start
 * @param deadRevalidationIntervalMinutes cooldown before a DEAD token is retried by the periodic
 *     validator
 * @param tokenFailoverMaxAttempts how many tokens to cycle through before surfacing a {@code
 *     TokenRejectedException} to the caller
 */
public record KodikTokenConfig(
        String tokenFile,
        @Nullable String bootstrapToken,
        boolean bootstrapFromEnv,
        boolean autoDiscoveryEnabled,
        boolean validateOnStartup,
        long deadRevalidationIntervalMinutes,
        int tokenFailoverMaxAttempts) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String tokenFile = "./data/kodik_tokens.json";
        @Nullable private String bootstrapToken;
        private boolean bootstrapFromEnv = true;
        private boolean autoDiscoveryEnabled = true;
        private boolean validateOnStartup = true;
        private long deadRevalidationIntervalMinutes = 1440;
        private int tokenFailoverMaxAttempts = 3;

        public Builder tokenFile(String value) {
            this.tokenFile = value;
            return this;
        }

        public Builder bootstrapToken(@Nullable String value) {
            this.bootstrapToken = value;
            return this;
        }

        public Builder bootstrapFromEnv(boolean value) {
            this.bootstrapFromEnv = value;
            return this;
        }

        public Builder autoDiscoveryEnabled(boolean value) {
            this.autoDiscoveryEnabled = value;
            return this;
        }

        public Builder validateOnStartup(boolean value) {
            this.validateOnStartup = value;
            return this;
        }

        public Builder deadRevalidationIntervalMinutes(long value) {
            this.deadRevalidationIntervalMinutes = value;
            return this;
        }

        public Builder tokenFailoverMaxAttempts(int value) {
            this.tokenFailoverMaxAttempts = value;
            return this;
        }

        public KodikTokenConfig build() {
            return new KodikTokenConfig(
                    tokenFile,
                    bootstrapToken,
                    bootstrapFromEnv,
                    autoDiscoveryEnabled,
                    validateOnStartup,
                    deadRevalidationIntervalMinutes,
                    tokenFailoverMaxAttempts);
        }
    }
}
