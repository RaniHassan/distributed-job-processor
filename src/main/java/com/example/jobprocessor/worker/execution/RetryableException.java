package com.example.jobprocessor.worker.execution;

/** A temporary failure (timeout, unavailable dependency) that a later attempt may not hit. */
public class RetryableException extends RuntimeException {

    public RetryableException(String message) {
        super(message);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
