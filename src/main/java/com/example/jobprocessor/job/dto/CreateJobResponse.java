package com.example.jobprocessor.job.dto;

import com.example.jobprocessor.job.domain.Job;
import com.example.jobprocessor.job.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;

/** Response of POST /api/v1/jobs (README §16.1). */
public record CreateJobResponse(UUID id, JobStatus status, Instant createdAt) {

    public static CreateJobResponse from(Job job) {
        return new CreateJobResponse(job.getId(), job.getStatus(), job.getCreatedAt());
    }
}
