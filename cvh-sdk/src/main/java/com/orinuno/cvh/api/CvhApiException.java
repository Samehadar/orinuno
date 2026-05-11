package com.orinuno.cvh.api;

import jakarta.annotation.Nullable;

/**
 * Wraps any error originating in {@link CvhApiClient} with a stable error code from {@link
 * com.orinuno.cvh.CvhErrorCodes}. The pipeline catches this and converts it into a {@link
 * com.orinuno.cvh.CvhDecodeResult#failure}.
 */
public final class CvhApiException extends RuntimeException {

    private final String errorCode;

    public CvhApiException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CvhApiException(String errorCode, @Nullable Throwable cause) {
        super(cause == null ? errorCode : cause.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
