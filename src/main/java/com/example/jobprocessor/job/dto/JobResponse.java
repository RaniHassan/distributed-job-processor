package com.example.jobprocessor.job.dto;

import com.example.jobprocessor.job.domain.Job;
import com.example.jobprocessor.job.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Job details (README §16.2). {@code completedAt} is set only for successful completion;
 * {@code finishedAt} is set whenever the job reaches any terminal state. Internal ownership
 * fields (worker, lease, fencing token) are deliberately not exposed.
 */
public record JobResponse(
        UUID id,
        JobStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant finishedAt,
        int retryCount,
        int maxRetries,
        String error) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getFinishedAt(),
                job.getRetryCount(),
                job.getMaxRetries(),
                job.getErrorMessage());
    }
}
