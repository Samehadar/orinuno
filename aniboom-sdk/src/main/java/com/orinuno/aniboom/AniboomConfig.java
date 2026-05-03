package com.orinuno.aniboom;

/**
 * Immutable configuration for {@link AniboomClient}. Use {@link #builder()} so additive fields stay
 * non-breaking.
 *
 * <p>Defaults match the production-tested values in orinuno-app: {@code https://aniboom.one/} as
 * the base and {@code https://animego.org/} as the Referer header — Aniboom's anti-hotlink check
 * accepts the animego.org Referer (it is the first-party host for the player) but not an empty one.
 */
public record AniboomConfig(String baseUrl, String referer, String userAgent) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl = "https://aniboom.one";
        private String referer = "https://animego.org/";
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

        public AniboomConfig build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            if (referer == null || referer.isBlank()) {
                throw new IllegalArgumentException(
                        "referer is required — Aniboom's anti-hotlink check rejects empty"
                                + " Referer");
            }
            if (userAgent == null || userAgent.isBlank()) {
                throw new IllegalArgumentException("userAgent is required");
            }
            return new AniboomConfig(baseUrl, referer, userAgent);
        }
    }
}
