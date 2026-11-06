package com.example.jobprocessor.worker.execution;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.simulation.FailureMode;
import com.example.jobprocessor.job.simulation.InvalidJobPayloadException;
import com.example.jobprocessor.job.simulation.SimulationSpec;
import com.example.jobprocessor.job.simulation.SimulationSpecParser;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The simulated business operation (README §2, §8.6): works for the requested or a random
 * duration in short slices, so the thread reacts to interruption within one slice instead of
 * sleeping through a multi-minute job. Injected failures follow {@code payload.simulation}.
 */
@Component
public class SimulatedJobExecutor {

    private final SimulationSpecParser specParser;
    private final Duration slice;
    private final Duration minDuration;
    private final Duration maxDuration;

    public SimulatedJobExecutor(SimulationSpecParser specParser, JobProcessorProperties properties) {
        this.specParser = specParser;
        this.slice = properties.worker().cancellationCheckInterval();
        this.minDuration = properties.simulation().minDuration();
        this.maxDuration = properties.simulation().maxDuration();
    }

    /**
     * @throws InterruptedException if the worker thread is interrupted; the job's outcome is then unknown
     * @throws RetryableException, NonRetryableException or another exception when the simulation fails
     */
    public void execute(ClaimedJob job) throws InterruptedException {
        SimulationSpec spec;
        try {
            spec = specParser.parse(job.payload());
        } catch (InvalidJobPayloadException e) {
            // Validated at creation; can only happen if configuration changed since. Retrying won't help.
            throw new NonRetryableException("Invalid job payload: " + e.getMessage(), e);
        }
        Duration total = spec.duration() != null ? spec.duration() : randomDuration();
        Duration failAt = failurePoint(spec, job.attemptNumber());

        work(failAt != null && failAt.compareTo(total) < 0 ? failAt : total);
        if (failAt != null) {
            throw failure(spec.failureMode());
        }
    }

    Duration randomDuration() {
        long min = minDuration.toMillis();
        long max = maxDuration.toMillis();
        return Duration.ofMillis(min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    /** When this attempt fails, or {@code null} if it succeeds. */
    private static Duration failurePoint(SimulationSpec spec, int attemptNumber) {
        Duration failAfter = spec.failAfter() != null ? spec.failAfter() : Duration.ZERO;
        return switch (spec.failureMode()) {
            case NONE -> null;
            case TRANSIENT_ONCE -> attemptNumber == 1 ? failAfter : null;
            case TRANSIENT_ALWAYS, PERMANENT, UNEXPECTED_ALWAYS -> failAfter;
        };
    }

    private static RuntimeException failure(FailureMode mode) {
        return switch (mode) {
            case TRANSIENT_ONCE, TRANSIENT_ALWAYS -> new RetryableException("Simulated transient failure");
            case PERMANENT -> new NonRetryableException("Simulated permanent failure");
            case UNEXPECTED_ALWAYS -> new IllegalStateException("Simulated unexpected failure");
            case NONE -> throw new IllegalArgumentException("NONE is not a failure");
        };
    }

    private void work(Duration duration) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, slice.toNanos()));
        }
    }
}
