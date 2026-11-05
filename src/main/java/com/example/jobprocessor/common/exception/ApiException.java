package com.example.jobprocessor.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A failure that is reported to the client as-is: an HTTP status, a stable machine-readable
 * error code and a detail message that is safe to expose.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    protected ApiException(HttpStatus status, String errorCode, String detail) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
