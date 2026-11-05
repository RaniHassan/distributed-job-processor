package com.example.jobprocessor.job.simulation;

/**
 * Simulated outcome of a job, for demonstrating and testing the retry policy. Anything other
 * than {@link #NONE} is failure injection and is accepted only when it is enabled (dev/test).
 */
public enum FailureMode {
    /** Completes successfully. */
    NONE,
    /** First attempt fails with a transient error; retries succeed. */
    TRANSIENT_ONCE,
    /** Every attempt fails with a transient error until retries are exhausted. */
    TRANSIENT_ALWAYS,
    /** Fails with a permanent error; never retried. */
    PERMANENT,
    /** Every attempt throws an unclassified exception (bounded retries per the retry policy). */
    UNEXPECTED_ALWAYS
}
