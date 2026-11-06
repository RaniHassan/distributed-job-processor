package com.example.jobprocessor.job.service;

import java.time.Duration;
import java.util.UUID;

/**
 * One job taken over by the Recovery Manager after its lease expired.
 *
 * @param retryDelay delay before the job becomes claimable again; {@code null} unless REQUEUED
 */
public record RecoveredJob(UUID jobId, String previousWorkerId, long fencingToken, Outcome outcome,
                           int retryCount, Duration retryDelay) {

    public enum Outcome {
        /** RUNNING with retries left: back to PENDING for another attempt. */
        REQUEUED,
        /** RUNNING with no retries left. */
        FAILED,
        /** Cancellation had been requested: finalized as CANCELLED. */
        CANCELLED
    }
}
