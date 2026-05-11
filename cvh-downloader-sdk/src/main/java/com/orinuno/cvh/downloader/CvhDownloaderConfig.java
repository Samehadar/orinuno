package com.orinuno.cvh.downloader;

import java.nio.file.Path;

/**
 * Immutable configuration for {@link CvhDownloader}. Use {@link #builder()} so additive fields stay
 * non-breaking.
 *
 * <p>Defaults match the production-tested values:
 *
 * <ul>
 *   <li>{@code segmentConcurrency = 8} — matches a typical home-broadband sweet spot for HLS.
 *   <li>{@code segmentRetryMaxAttempts = 4}, {@code segmentRetryBaseDelayMs = 250} — linear backoff
 *       up to 1s on transient 5xx/429.
 *   <li>{@code ffmpegBinary = "ffmpeg"} — resolved via PATH.
 *   <li>{@code ffmpegTimeoutSeconds = 600} — bound runaway processes.
 *   <li>{@code maxBytesPerFile = 5 GB} — guard against malicious or runaway segment lists.
 *   <li>{@code mp4ParallelChunks = 4} — number of parallel {@code Range} GETs for direct MP4.
 *       Empirically the CVH CDN ({@code ok6-1.vkuser.net}) caps a single TCP stream around ~1 MB/s,
 *       so 4 parallel chunks deliver ~4x throughput.
 *   <li>{@code mp4MinChunkBytes = 1 MB} — minimum size of one parallel chunk. Below this the
 *       strategy falls back to a single-stream GET to avoid overhead from N small connections.
 *   <li>{@code mp4ParallelEnabled = true} — feature flag for the parallel path.
 * </ul>
 */
public record CvhDownloaderConfig(
        Path outputBaseDir,
        int segmentConcurrency,
        int segmentRetryMaxAttempts,
        long segmentRetryBaseDelayMs,
        String ffmpegBinary,
        int ffmpegTimeoutSeconds,
        long maxBytesPerFile,
        String userAgent,
        int mp4ParallelChunks,
        long mp4MinChunkBytes,
        boolean mp4ParallelEnabled) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path outputBaseDir = Path.of("./data/videos");
        private int segmentConcurrency = 8;
        private int segmentRetryMaxAttempts = 4;
        private long segmentRetryBaseDelayMs = 250;
        private String ffmpegBinary = "ffmpeg";
        private int ffmpegTimeoutSeconds = 600;
        private long maxBytesPerFile = 5L * 1024 * 1024 * 1024;
        private String userAgent =
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                        + " Gecko) Chrome/147.0.0.0 Safari/537.36";
        private int mp4ParallelChunks = 4;
        private long mp4MinChunkBytes = 1024L * 1024L;
        private boolean mp4ParallelEnabled = true;

        private Builder() {}

        public Builder outputBaseDir(Path outputBaseDir) {
            this.outputBaseDir = outputBaseDir;
            return this;
        }

        public Builder segmentConcurrency(int segmentConcurrency) {
            this.segmentConcurrency = segmentConcurrency;
            return this;
        }

        public Builder segmentRetryMaxAttempts(int attempts) {
            this.segmentRetryMaxAttempts = attempts;
            return this;
        }

        public Builder segmentRetryBaseDelayMs(long ms) {
            this.segmentRetryBaseDelayMs = ms;
            return this;
        }

        public Builder ffmpegBinary(String ffmpegBinary) {
            this.ffmpegBinary = ffmpegBinary;
            return this;
        }

        public Builder ffmpegTimeoutSeconds(int seconds) {
            this.ffmpegTimeoutSeconds = seconds;
            return this;
        }

        public Builder maxBytesPerFile(long maxBytesPerFile) {
            this.maxBytesPerFile = maxBytesPerFile;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder mp4ParallelChunks(int chunks) {
            this.mp4ParallelChunks = chunks;
            return this;
        }

        public Builder mp4MinChunkBytes(long bytes) {
            this.mp4MinChunkBytes = bytes;
            return this;
        }

        public Builder mp4ParallelEnabled(boolean enabled) {
            this.mp4ParallelEnabled = enabled;
            return this;
        }

        public CvhDownloaderConfig build() {
            if (outputBaseDir == null) {
                throw new IllegalArgumentException("outputBaseDir is required");
            }
            if (segmentConcurrency < 1) {
                throw new IllegalArgumentException("segmentConcurrency must be >= 1");
            }
            if (segmentRetryMaxAttempts < 0) {
                throw new IllegalArgumentException("segmentRetryMaxAttempts must be >= 0");
            }
            if (segmentRetryBaseDelayMs < 0) {
                throw new IllegalArgumentException("segmentRetryBaseDelayMs must be >= 0");
            }
            if (ffmpegBinary == null || ffmpegBinary.isBlank()) {
                throw new IllegalArgumentException("ffmpegBinary is required");
            }
            if (ffmpegTimeoutSeconds < 1) {
                throw new IllegalArgumentException("ffmpegTimeoutSeconds must be >= 1");
            }
            if (maxBytesPerFile < 1) {
                throw new IllegalArgumentException("maxBytesPerFile must be >= 1");
            }
            if (userAgent == null || userAgent.isBlank()) {
                throw new IllegalArgumentException("userAgent is required");
            }
            if (mp4ParallelChunks < 1) {
                throw new IllegalArgumentException("mp4ParallelChunks must be >= 1");
            }
            if (mp4MinChunkBytes < 1) {
                throw new IllegalArgumentException("mp4MinChunkBytes must be >= 1");
            }
            return new CvhDownloaderConfig(
                    outputBaseDir,
                    segmentConcurrency,
                    segmentRetryMaxAttempts,
                    segmentRetryBaseDelayMs,
                    ffmpegBinary,
                    ffmpegTimeoutSeconds,
                    maxBytesPerFile,
                    userAgent,
                    mp4ParallelChunks,
                    mp4MinChunkBytes,
                    mp4ParallelEnabled);
        }
    }
}
