package com.example.jobprocessor.job.simulation;

import java.time.Duration;

/**
 * Validated simulation controls from {@code payload.simulation}.
 *
 * @param duration   requested processing time, or {@code null} for a random duration in the configured range
 * @param failureMode simulated outcome
 * @param failAfter  how long the attempt runs before an injected failure, or {@code null} for immediately
 */
public record SimulationSpec(Duration duration, FailureMode failureMode, Duration failAfter) {

    public static final SimulationSpec DEFAULT = new SimulationSpec(null, FailureMode.NONE, null);
}
