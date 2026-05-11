package com.orinuno.cvh;

/**
 * Immutable configuration for {@link CvhClient}. Use {@link #builder()} so additive fields stay
 * non-breaking.
 *
 * <p>Host base URLs are intentionally absent — each {@link com.orinuno.cvh.host.CvhHostPageParser}
 * carries its own host base. This SDK is host-agnostic; {@code CvhConfig} only describes the CVH
 * backend (plapi + player origin) and shared HTTP headers.
 *
 * <p>{@code referer} is required: CVH plapi rejects requests with no Referer (returns 403). The
 * default {@code https://player.cdnvideohub.com/} matches the value the real CVH iframe sends.
 */
public record CvhConfig(
        String plapiBaseUrl,
        String playerBaseUrl,
        String referer,
        String userAgent,
        String defaultAggregator,
        int tokenRefreshMarginMinutes,
        int maxCacheEntries) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String plapiBaseUrl = "https://plapi.cdnvideohub.com";
        private String playerBaseUrl = "https://player.cdnvideohub.com";
        private String referer = "https://player.cdnvideohub.com/";
        private String userAgent =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/147.0.0.0 Safari/537.36";
        private String defaultAggregator = "mali";
        private int tokenRefreshMarginMinutes = 30;
        private int maxCacheEntries = 1024;

        private Builder() {}

        public Builder plapiBaseUrl(String plapiBaseUrl) {
            this.plapiBaseUrl = plapiBaseUrl;
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

        public Builder defaultAggregator(String defaultAggregator) {
            this.defaultAggregator = defaultAggregator;
            return this;
        }

        public Builder tokenRefreshMarginMinutes(int minutes) {
            this.tokenRefreshMarginMinutes = minutes;
            return this;
        }

        /**
         * Maximum number of signed-URL bundles to retain in {@link
         * com.orinuno.cvh.api.CvhVideoSourcesCache}. The cache evicts the least-recently-used entry
         * on overflow — without a bound, a caller could keep pushing distinct vkIds and OOM the
         * process. Default 1024 (~1 MB).
         */
        public Builder maxCacheEntries(int maxCacheEntries) {
            this.maxCacheEntries = maxCacheEntries;
            return this;
        }

        public CvhConfig build() {
            if (plapiBaseUrl == null || plapiBaseUrl.isBlank()) {
                throw new IllegalArgumentException("plapiBaseUrl is required");
            }
            if (!plapiBaseUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "plapiBaseUrl must use https:// — signed CDN URLs and Referer headers"
                                + " would otherwise leak over plaintext HTTP");
            }
            if (playerBaseUrl == null || playerBaseUrl.isBlank()) {
                throw new IllegalArgumentException("playerBaseUrl is required");
            }
            if (!playerBaseUrl.startsWith("https://")) {
                throw new IllegalArgumentException("playerBaseUrl must use https://");
            }
            if (referer == null) {
                throw new IllegalArgumentException("referer must not be null (use \"\" to defer)");
            }
            if (userAgent == null || userAgent.isBlank()) {
                throw new IllegalArgumentException("userAgent is required");
            }
            if (defaultAggregator == null || defaultAggregator.isBlank()) {
                throw new IllegalArgumentException("defaultAggregator is required");
            }
            if (tokenRefreshMarginMinutes < 0) {
                throw new IllegalArgumentException("tokenRefreshMarginMinutes must be >= 0");
            }
            if (maxCacheEntries < 1) {
                throw new IllegalArgumentException("maxCacheEntries must be >= 1");
            }
            return new CvhConfig(
                    plapiBaseUrl,
                    playerBaseUrl,
                    referer,
                    userAgent,
                    defaultAggregator,
                    tokenRefreshMarginMinutes,
                    maxCacheEntries);
        }
    }
}
