package com.example.jobprocessor.job.service;

/** What was recorded for a worker's result. */
public enum RecordedOutcome {
    COMPLETED,
    /** Failed attempt with retries left: the job is PENDING again, after a backoff. */
    RETRY_SCHEDULED,
    FAILED,
    /** Cancellation had been requested; it takes precedence over the worker's result. */
    CANCELLED,
    /** This worker no longer owns the job; nothing was written. */
    OWNERSHIP_LOST
}
