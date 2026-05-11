package com.orinuno.cvh.downloader;

import jakarta.annotation.Nullable;

/** Carrier for a stable {@link CvhDownloaderErrorCodes} value. */
public final class CvhDownloaderException extends RuntimeException {

    private final String errorCode;

    public CvhDownloaderException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CvhDownloaderException(String errorCode, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
