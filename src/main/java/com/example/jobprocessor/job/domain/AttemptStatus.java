package com.example.jobprocessor.job.domain;

public enum AttemptStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ABANDONED;

    public boolean isFinal() {
        return this != RUNNING;
    }

    /** An attempt is opened once (RUNNING) and closed exactly once into a final status. */
    public boolean canTransitionTo(AttemptStatus target) {
        return this == RUNNING && target.isFinal();
    }
}
