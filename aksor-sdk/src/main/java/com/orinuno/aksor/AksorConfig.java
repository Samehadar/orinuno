package com.orinuno.aksor;

/**
 * Immutable configuration for {@link AksorClient}. Use {@link #builder()} so additive fields stay
 * non-breaking.
 *
 * <p>Host base URLs (e.g. yummyani.me) are NOT here — each {@link
 * com.orinuno.aksor.host.AksorHostPageParser} carries its own host base. {@code AksorConfig} only
 * describes the Aksor player backend (api + origin) and shared HTTP headers.
 */
public record AksorConfig(
        String apiBaseUrl,
        String playerBaseUrl,
        String referer,
        String userAgent,
        int episodeFetchConcurrency,
        int requestTimeoutSeconds) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiBaseUrl = "https://player.aksor.tv";
        private String playerBaseUrl = "https://player.aksor.tv";
        private String referer = "https://player.aksor.tv/";
        private String userAgent =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/147.0.0.0 Safari/537.36";
        private int episodeFetchConcurrency = 4;
        private int requestTimeoutSeconds = 20;

        private Builder() {}

        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        public Builder playerBaseUrl(String playerBaseUrl) {
            this.playerBaseUrl = playerBaseUrl;
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

        public Builder episodeFetchConcurrency(int episodeFetchConcurrency) {
            this.episodeFetchConcurrency = episodeFetchConcurrency;
            return this;
        }

        public Builder requestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
            return this;
        }

        public AksorConfig build() {
            if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
                throw new IllegalArgumentException("apiBaseUrl is required");
            }
            if (!apiBaseUrl.startsWith("https://")) {
                throw new IllegalArgumentException("apiBaseUrl must use https://");
            }
            if (playerBaseUrl == null || playerBaseUrl.isBlank()) {
                throw new IllegalArgumentException("playerBaseUrl is required");
            }
            if (!playerBaseUrl.startsWith("https://")) {
                throw new IllegalArgumentException("playerBaseUrl must use https://");
            }
            if (referer == null) {
                throw new IllegalArgumentException("referer must not be null");
            }
            if (userAgent == null || userAgent.isBlank()) {
                throw new IllegalArgumentException("userAgent is required");
            }
            if (episodeFetchConcurrency < 1) {
                throw new IllegalArgumentException("episodeFetchConcurrency must be >= 1");
            }
            if (requestTimeoutSeconds < 1) {
                throw new IllegalArgumentException("requestTimeoutSeconds must be >= 1");
            }
            return new AksorConfig(
                    apiBaseUrl,
                    playerBaseUrl,
                    referer,
                    userAgent,
                    episodeFetchConcurrency,
                    requestTimeoutSeconds);
        }
    }
}
