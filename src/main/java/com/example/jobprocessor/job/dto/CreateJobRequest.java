package com.example.jobprocessor.job.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** Body of POST /api/v1/jobs. The payload must be a JSON object. */
public record CreateJobRequest(@NotNull Map<String, Object> payload) {
}
