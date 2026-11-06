package com.example.jobprocessor.job.service;

/** Result of a heartbeat for one claimed job. */
public enum LeaseStatus {
    /** Still owned and RUNNING; the lease was extended. */
    RENEWED,
    /** Still owned, but the owner asked for cancellation; the lease is deliberately not extended. */
    CANCEL_REQUESTED,
    /** This worker no longer owns the job (recovered, reclaimed or finished). */
    LOST
}
