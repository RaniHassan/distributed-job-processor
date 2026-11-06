package com.example.jobprocessor.job.lifecycle;

/** Classification of a failed attempt (README §12). Cancellation is never a failure type. */
public enum FailureType {
    /** Temporary condition (timeout, network); eligible for retry. */
    TRANSIENT,
    /** Retrying cannot help (invalid input, unsupported operation). */
    PERMANENT,
    /** Unclassified exception; handled by an explicit, bounded policy. */
    UNEXPECTED
}
