package com.orinuno.cvh.downloader.hls;

/**
 * Segment-level retry decision. Folds HTTP 5xx, 408, 425, and 429 into a single "should-I-retry?"
 * check so the caller stays small and tested.
 *
 * <p>Status codes we retry:
 *
 * <ul>
 *   <li>{@code 408} — Request Timeout
 *   <li>{@code 425} — Too Early (HTTP/3 / TLS resumption noise)
 *   <li>{@code 429} — Too Many Requests
 *   <li>Any {@code 5xx} — transient upstream / origin failure
 * </ul>
 *
 * <p>Everything else (4xx other than the above) fast-fails — retrying a 404/403/451 just wastes
 * latency and amplifies the broken link upstream.
 */
public final class HlsRetryPolicy {

    private HlsRetryPolicy() {}

    public static boolean isRetriableStatus(int httpStatus) {
        if (httpStatus == 408 || httpStatus == 425 || httpStatus == 429) {
            return true;
        }
        return httpStatus >= 500 && httpStatus <= 599;
    }

    /**
     * Linear backoff floor: {@code baseDelayMs * attempt}. Deliberately linear (not exponential) —
     * HLS segments are tiny and we want the next attempt to land while the CDN edge is still warm.
     * {@code attempt} is 1-based.
     */
    public static long backoffMillis(long baseDelayMs, int attempt) {
        if (baseDelayMs <= 0 || attempt <= 0) {
            return 0L;
        }
        return baseDelayMs * attempt;
    }
}
