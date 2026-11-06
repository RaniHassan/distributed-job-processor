package com.example.jobprocessor.worker.execution;

import com.example.jobprocessor.job.lifecycle.FailureType;

/**
 * Maps execution exceptions to failure types using the explicit exception categories only;
 * nothing is treated as retryable by guesswork. A categorized exception wrapped by another one
 * keeps its category.
 */
public final class FailureClassifier {

    private FailureClassifier() {
    }

    public static FailureType classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof RetryableException) {
                return FailureType.TRANSIENT;
            }
            if (t instanceof NonRetryableException) {
                return FailureType.PERMANENT;
            }
        }
        return FailureType.UNEXPECTED;
    }

    /**
     * Message stored on the job and visible to its owner. Categorized failures carry a message
     * written for clients; unexpected ones may contain internals, so only a generic text is kept.
     */
    public static String clientMessage(FailureType type, Throwable failure) {
        return type == FailureType.UNEXPECTED ? "Unexpected processing error" : String.valueOf(failure.getMessage());
    }
}
