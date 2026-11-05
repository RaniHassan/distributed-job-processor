package com.example.jobprocessor.job.dto;

import com.example.jobprocessor.job.domain.AttemptStatus;
import com.example.jobprocessor.job.domain.JobAttempt;
import java.time.Instant;

/** One entry of GET /api/v1/jobs/{id}/attempts. Worker identity and fencing tokens stay internal. */
public record JobAttemptResponse(
        int attemptNumber,
        AttemptStatus status,
        Instant startedAt,
        Instant finishedAt,
        String error) {

    public static JobAttemptResponse from(JobAttempt attempt) {
        return new JobAttemptResponse(
                attempt.getAttemptNumber(),
                attempt.getStatus(),
                attempt.getStartedAt(),
                attempt.getFinishedAt(),
                attempt.getErrorMessage());
    }
}
