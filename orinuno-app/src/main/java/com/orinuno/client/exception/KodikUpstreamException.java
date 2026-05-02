package com.orinuno.client.exception;

/**
 * Generic upstream failure that didn't carry a recognisable Kodik error body. HTTP status is
 * preserved so callers can distinguish e.g. 502 (CDN issue, retryable) from 500 (Kodik app crash,
 * also retryable but more concerning).
 */
public class KodikUpstreamException extends KodikApiException {

    private final int httpStatus;
    private final String bodyPreview;

    public KodikUpstreamException(int httpStatus, String bodyPreview) {
        super(
                "Kodik upstream error "
                        + httpStatus
                        + " (body~="
                        + (bodyPreview == null ? "" : bodyPreview)
                        + ")");
        this.httpStatus = httpStatus;
        this.bodyPreview = bodyPreview;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getBodyPreview() {
        return bodyPreview;
    }
}
