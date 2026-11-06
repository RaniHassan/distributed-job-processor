package com.example.jobprocessor.job.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A job this worker owns. {@code workerId} and {@code fencingToken} identify the ownership
 * generation: every update made on behalf of this claim is conditional on both.
 */
public record ClaimedJob(UUID jobId, String workerId, long fencingToken, Map<String, Object> payload) {

    public ClaimedJob {
        // Not Map.copyOf: JSON payloads may legitimately contain null values.
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Each claim creates exactly one attempt, numbered with the claim's fencing token. */
    public int attemptNumber() {
        return Math.toIntExact(fencingToken);
    }
}
