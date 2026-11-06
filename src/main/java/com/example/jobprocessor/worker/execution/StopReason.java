package com.example.jobprocessor.worker.execution;

/** Why a running job was told to stop before finishing. */
public enum StopReason {
    /** The job's owner requested cancellation. */
    CANCELLATION_REQUESTED,
    /** This worker no longer owns the job (it was recovered or reclaimed elsewhere). */
    OWNERSHIP_LOST,
    /** The lease could not be confirmed before it would expire (e.g. database unreachable). */
    LEASE_EXPIRED
}
