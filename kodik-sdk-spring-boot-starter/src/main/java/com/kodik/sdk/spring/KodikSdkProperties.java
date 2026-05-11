package com.kodik.sdk.spring;

import com.kodik.token.KodikTokenConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot {@code @ConfigurationProperties} binding for the kodik-sdk-spring-boot-starter. Maps
 * the {@code kodik.sdk.*} property prefix into a typed record consumed by {@link
 * KodikSdkAutoConfiguration}.
 *
 * <p>The shape mirrors the existing {@code orinuno.kodik.*} layout from orinuno-app — any service
 * that re-binds {@code orinuno.kodik} → {@code kodik.sdk} via Spring's relaxed-binding gets
 * identical behaviour. Consumers that want a different prefix declare their own
 * {@code @ConfigurationProperties} class and disable this one.
 */
@ConfigurationProperties("kodik.sdk")
public class KodikSdkProperties {

    /** Per-minute permit budget for KodikApiRateLimiter. */
    private int rateLimitPerMinute = 30;

    /** Token subsystem configuration. */
    private Token token = new Token();

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public Token getToken() {
        return token;
    }

    public void setToken(Token token) {
        this.token = token;
    }

    /**
     * Translate this property bag into the SDK-side record. Defaults applied here mirror {@link
     * KodikTokenConfig.Builder} defaults.
     */
    public KodikTokenConfig toTokenConfig() {
        return KodikTokenConfig.builder()
                .tokenFile(token.getFile())
                .bootstrapToken(token.getBootstrapValue())
                .bootstrapFromEnv(token.isBootstrapFromEnv())
                .autoDiscoveryEnabled(token.isAutoDiscoveryEnabled())
                .validateOnStartup(token.isValidateOnStartup())
                .deadRevalidationIntervalMinutes(token.getDeadRevalidationIntervalMinutes())
                .tokenFailoverMaxAttempts(token.getFailoverMaxAttempts())
                .build();
    }

    /** Nested {@code kodik.sdk.token.*} property group. */
    public static class Token {
        private String file = "./data/kodik_tokens.json";
        private String bootstrapValue;
        private boolean bootstrapFromEnv = true;
        private boolean autoDiscoveryEnabled = true;
        private boolean validateOnStartup = true;
        private long deadRevalidationIntervalMinutes = 1440;
        private int failoverMaxAttempts = 3;

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }

        public String getBootstrapValue() {
            return bootstrapValue;
        }

        public void setBootstrapValue(String bootstrapValue) {
            this.bootstrapValue = bootstrapValue;
        }

        public boolean isBootstrapFromEnv() {
            return bootstrapFromEnv;
        }

        public void setBootstrapFromEnv(boolean bootstrapFromEnv) {
            this.bootstrapFromEnv = bootstrapFromEnv;
        }

        public boolean isAutoDiscoveryEnabled() {
            return autoDiscoveryEnabled;
        }

        public void setAutoDiscoveryEnabled(boolean autoDiscoveryEnabled) {
            this.autoDiscoveryEnabled = autoDiscoveryEnabled;
        }

        public boolean isValidateOnStartup() {
            return validateOnStartup;
        }

        public void setValidateOnStartup(boolean validateOnStartup) {
            this.validateOnStartup = validateOnStartup;
        }

        public long getDeadRevalidationIntervalMinutes() {
            return deadRevalidationIntervalMinutes;
        }

        public void setDeadRevalidationIntervalMinutes(long deadRevalidationIntervalMinutes) {
            this.deadRevalidationIntervalMinutes = deadRevalidationIntervalMinutes;
        }

        public int getFailoverMaxAttempts() {
            return failoverMaxAttempts;
        }

        public void setFailoverMaxAttempts(int failoverMaxAttempts) {
            this.failoverMaxAttempts = failoverMaxAttempts;
        }
    }
}
