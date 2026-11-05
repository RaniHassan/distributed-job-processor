package com.example.jobprocessor.job.lifecycle;

import com.example.jobprocessor.job.domain.JobStatus;

public class IllegalStateTransitionException extends RuntimeException {

    private final JobStatus from;
    private final JobStatus to;

    public IllegalStateTransitionException(JobStatus from, JobStatus to) {
        super("Illegal job state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public JobStatus from() {
        return from;
    }

    public JobStatus to() {
        return to;
    }
}
