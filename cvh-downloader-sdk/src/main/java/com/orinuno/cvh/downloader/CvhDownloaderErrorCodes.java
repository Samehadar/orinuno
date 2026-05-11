package com.orinuno.cvh.downloader;

/** Stable error vocabulary surfaced by {@link CvhDownloader}. */
public final class CvhDownloaderErrorCodes {

    /** {@link CvhDownloader} could not derive a single playable URL from the supplied sources. */
    public static final String CVH_DL_NO_CANDIDATES = "CVH_DL_NO_CANDIDATES";

    /** Every candidate in the fallback chain failed. */
    public static final String CVH_DL_ALL_FAILED = "CVH_DL_ALL_FAILED";

    /** Network failure (TCP/DNS/TLS/timeout) during direct-MP4 fetch or HLS segment GET. */
    public static final String CVH_DL_NETWORK = "CVH_DL_NETWORK";

    /** ffmpeg exited non-zero, timed out, or could not be invoked. */
    public static final String CVH_DL_FFMPEG_FAILED = "CVH_DL_FFMPEG_FAILED";

    /** Local filesystem error (write/move/create-dir/permission). */
    public static final String CVH_DL_OUTPUT_IO = "CVH_DL_OUTPUT_IO";

    /** Caller request rejected — bad output path, bad filename, http:// URL, oversized cap, etc. */
    public static final String CVH_DL_INVALID_REQUEST = "CVH_DL_INVALID_REQUEST";

    /** Downloaded payload exceeded {@code CvhDownloaderConfig.maxBytesPerFile}. */
    public static final String CVH_DL_TOO_LARGE = "CVH_DL_TOO_LARGE";

    private CvhDownloaderErrorCodes() {}
}
