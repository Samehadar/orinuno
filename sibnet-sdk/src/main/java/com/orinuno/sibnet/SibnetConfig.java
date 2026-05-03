package com.orinuno.sibnet;

/**
 * Immutable configuration for {@link SibnetClient}. Use {@link #builder()} so additive fields stay
 * non-breaking.
 *
 * <p>Defaults match the production-tested values in orinuno-app: {@code https://video.sibnet.ru/}
 * as the base URL and as the Referer header (Sibnet's anti-hotlink check rejects requests with any
 * other Referer or no Referer at all), and a real desktop User-Agent.
 */
public record SibnetConfig(String baseUrl, String referer, String userAgent) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl = "https://video.sibnet.ru";
        private String referer = "https://video.sibnet.ru/";
        private String userAgent =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/147.0.0.0 Safari/537.36";

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder referer(String referer) {
            this.referer = referer;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public SibnetConfig build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            if (referer == null || referer.isBlank()) {
                throw new IllegalArgumentException(
                        "referer is required — Sibnet's anti-hotlink check rejects empty Referer");
            }
            if (userAgent == null || userAgent.isBlank()) {
                throw new IllegalArgumentException("userAgent is required");
            }
            return new SibnetConfig(baseUrl, referer, userAgent);
        }
    }
}
