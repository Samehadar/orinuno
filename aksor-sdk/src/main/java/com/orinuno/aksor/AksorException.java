package com.orinuno.aksor;

import jakarta.annotation.Nullable;

/** Carrier for a stable {@link AksorErrorCodes} value. */
public final class AksorException extends RuntimeException {

    private final String errorCode;

    public AksorException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AksorException(String errorCode, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
