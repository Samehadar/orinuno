package com.orinuno.service.download.hls;

/**
 * DOWNLOAD-PARALLEL — segment-level retry decision. Pre-DOWNLOAD-PARALLEL the segment loop only
 * retried on {@link java.io.IOException}; HTTP 5xx and 429 fast-failed silently which produced
 * holes in the final {@code .ts} file. This policy folds both error families into a single
 * "should-I-retry?" check so the caller stays small + tested.
 *
 * <p>Status codes we retry:
 *
 * <ul>
 *   <li>{@code 408} — Request Timeout
 *   <li>{@code 425} — Too Early (HTTP/3 / TLS resumption noise)
 *   <li>{@code 429} — Too Many Requests (Kodik's CDN occasionally throttles burst fetches)
 *   <li>Any {@code 5xx} (500, 502, 503, 504, ...) — transient upstream / origin failure
 * </ul>
 *
 * <p>Everything else (4xx other than the above) fast-fails — retrying a 404/403/451 just wastes
 * latency and amplifies the broken link on Kodik's side.
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
     * Linear backoff floor: {@code baseDelayMs * attempt}. We deliberately stay linear (not
     * exponential) — HLS segments are tiny and we want the next attempt to land while the CDN edge
     * is still warm. {@code attempt} is 1-based.
     */
    public static long backoffMillis(long baseDelayMs, int attempt) {
        if (baseDelayMs <= 0 || attempt <= 0) {
            return 0L;
        }
        return baseDelayMs * attempt;
    }
}
