package com.example.jobprocessor.job.service;

public enum CancelOutcome {
    /** A PENDING job was cancelled immediately. */
    CANCELLED,
    /** A RUNNING job was asked to stop; its worker finishes the cancellation. */
    CANCEL_REQUESTED,
    /** Repeated request: the job was already cancelled. */
    ALREADY_CANCELLED,
    /** Repeated request: cancellation was already requested and is in progress. */
    ALREADY_CANCEL_REQUESTED
}
