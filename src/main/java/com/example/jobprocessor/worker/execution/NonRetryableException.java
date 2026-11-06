package com.example.jobprocessor.worker.execution;

/** A failure that retrying cannot fix, such as invalid input or an unsupported operation. */
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
