package com.example.jobprocessor.job.domain;

public enum JobStatus {
    PENDING,
    RUNNING,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** States in which a worker owns the job and the row carries worker_id and lease_until. */
    public boolean isOwned() {
        return this == RUNNING || this == CANCEL_REQUESTED;
    }
}
