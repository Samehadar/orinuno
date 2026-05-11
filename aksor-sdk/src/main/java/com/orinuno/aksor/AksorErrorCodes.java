package com.orinuno.aksor;

/** Stable error vocabulary returned by {@link AksorClient}. */
public final class AksorErrorCodes {

    /** Page URL did not match any registered host parser. */
    public static final String AKSOR_UNSUPPORTED_HOST = "AKSOR_UNSUPPORTED_HOST";

    /** Network error fetching the host page (timeout, DNS, TLS, non-2xx). */
    public static final String AKSOR_FETCH_ERROR = "AKSOR_FETCH_ERROR";

    /** Host page parsed but the anime/episode metadata could not be extracted. */
    public static final String AKSOR_PAGE_PARSE_ERROR = "AKSOR_PAGE_PARSE_ERROR";

    /** Page parsed but did not embed any Aksor episodes. */
    public static final String AKSOR_NO_EPISODES = "AKSOR_NO_EPISODES";

    /** Episodes are present but {@link AksorEpisodeFilter} excluded every one of them. */
    public static final String AKSOR_NO_EPISODES_MATCHED = "AKSOR_NO_EPISODES_MATCHED";

    /** {@code player.aksor.tv/api/video/{hash}} returned an error or unparseable body. */
    public static final String AKSOR_API_ERROR = "AKSOR_API_ERROR";

    /** API response contained no playable qualities. */
    public static final String AKSOR_NO_QUALITIES = "AKSOR_NO_QUALITIES";

    private AksorErrorCodes() {}
}
