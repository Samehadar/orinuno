package com.orinuno.cvh;

/**
 * Stable error-code vocabulary returned by {@link CvhClient#decode}. Operators grep these in
 * alerts; treat them as a stable contract.
 */
public final class CvhErrorCodes {

    /** Page URL did not match any registered {@link com.orinuno.cvh.host.CvhHostPageParser}. */
    public static final String CVH_UNSUPPORTED_HOST = "CVH_UNSUPPORTED_HOST";

    /** Network error fetching the host page (timeout, DNS, TLS, non-2xx). */
    public static final String CVH_FETCH_ERROR = "CVH_FETCH_ERROR";

    /** Host page fetched but parser could not extract a usable {@code AnimeContent}. */
    public static final String CVH_PAGE_PARSE_ERROR = "CVH_PAGE_PARSE_ERROR";

    /**
     * Host page parsed but no {@code <video-player>} element found (page lacks the CVH embed). The
     * caller may still consume the metadata; {@code tracks} will be empty.
     */
    public static final String CVH_NO_PLAYER = "CVH_NO_PLAYER";

    /** CVH plapi returned an error or unparseable JSON. */
    public static final String CVH_API_ERROR = "CVH_API_ERROR";

    /** Title endpoint returned zero items — nothing playable. */
    public static final String CVH_NO_TRACKS = "CVH_NO_TRACKS";

    /** {@code /sv/video/{vkId}} returned no sources or the response shape changed. */
    public static final String CVH_VIDEO_NOT_FOUND = "CVH_VIDEO_NOT_FOUND";

    private CvhErrorCodes() {}
}
