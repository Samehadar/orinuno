package com.orinuno.jutsu;

import jakarta.annotation.Nullable;
import java.time.Duration;

/**
 * Immutable configuration for {@link JutsuClient}. Use {@link #builder()} to build instances —
 * direct constructor invocation is discouraged so adding new fields stays a non-breaking change.
 *
 * <p>Defaults match the production-tested values in {@code orinuno-app} so an SDK consumer with no
 * tuning input gets the same behaviour as orinuno itself: 1 RPS hard cap, 4-hour session TTL,
 * 15-second login timeout, https://jut.su as the base URL.
 *
 * <p>Credentials ({@link #username()} / {@link #password()}) are nullable. When unset, the SDK runs
 * in anonymous mode: no DLE login is performed, premium-gated episodes return {@link
 * JutsuErrorCodes#JUTSU_PREMIUM_REQUIRED} without retry. Set both to a real {@code Jutsu+} account
 * to unlock premium content.
 *
 * <p>{@link #userAgent()} is the sticky desktop User-Agent the SDK sends with every request.
 * Required: jut.su's bot-detection rejects empty / curl-default UAs, and a stable UA across the
 * session is the only one that matches what their fingerprint check expects.
 */
public record JutsuConfig(
        String baseUrl,
        @Nullable String username,
        @Nullable String password,
        String userAgent,
        double rateLimitRps,
        Duration sessionTtl,
        Duration loginTimeout) {

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for {@link JutsuConfig}. All defaults match what {@code orinuno-app} ships
     * out of the box.
     */
    public static final class Builder {
        private String baseUrl = "https://jut.su";
        @Nullable private String username = null;
        @Nullable private String password = null;
        private String userAgent =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/147.0.0.0 Safari/537.36";
        private double rateLimitRps = 1.0;
        private Duration sessionTtl = Duration.ofMinutes(240);
        private Duration loginTimeout = Duration.ofSeconds(15);

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder credentials(@Nullable String username, @Nullable String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder rateLimitRps(double rateLimitRps) {
            this.rateLimitRps = rateLimitRps;
            return this;
        }

        public Builder sessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
            return this;
        }

        public Builder loginTimeout(Duration loginTimeout) {
            this.loginTimeout = loginTimeout;
            return this;
        }

        public JutsuConfig build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            if (userAgent == null || userAgent.isBlank()) {
                throw new IllegalArgumentException(
                        "userAgent is required — jut.su rejects empty / default UAs");
            }
            if (rateLimitRps <= 0) {
                throw new IllegalArgumentException("rateLimitRps must be > 0");
            }
            if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
                throw new IllegalArgumentException("sessionTtl must be positive");
            }
            if (loginTimeout == null || loginTimeout.isNegative() || loginTimeout.isZero()) {
                throw new IllegalArgumentException("loginTimeout must be positive");
            }
            return new JutsuConfig(
                    baseUrl, username, password, userAgent, rateLimitRps, sessionTtl, loginTimeout);
        }
    }
}
