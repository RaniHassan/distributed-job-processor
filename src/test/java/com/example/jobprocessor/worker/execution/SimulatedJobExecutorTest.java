package com.example.jobprocessor.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.simulation.SimulationSpecParser;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Durations here are below the configured 1-minute minimum on purpose: the executor trusts the
 * parser, so a parser stub would hide nothing. Specs are passed through the real parser with
 * failure injection enabled and range checks avoided by using {@link #executeFor}.
 */
class SimulatedJobExecutorTest {

    private final JobProcessorProperties properties = TestProperties.create(true);
    private final SimulatedJobExecutor executor = new SimulatedJobExecutor(new ShortRangeParser(properties), properties);

    @Test
    void successfulRunTakesTheRequestedDuration() throws Exception {
        long elapsed = timed(() -> run(executor, job(1, Map.of("durationMs", 300))));

        assertThat(elapsed).isBetween(280L, 1500L);
    }

    @Test
    void transientOnceFailsOnlyTheFirstAttempt() throws Exception {
        assertThatThrownBy(() -> run(executor, job(1, Map.of("durationMs", 50, "failureMode", "TRANSIENT_ONCE"))))
                .isInstanceOf(RetryableException.class);
        run(executor, job(2, Map.of("durationMs", 50, "failureMode", "TRANSIENT_ONCE")));
    }

    @Test
    void transientAlwaysFailsEveryAttempt() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            int n = attempt;
            assertThatThrownBy(() -> run(executor, job(n, Map.of("durationMs", 50, "failureMode", "TRANSIENT_ALWAYS"))))
                    .isInstanceOf(RetryableException.class);
        }
    }

    @Test
    void permanentAndUnexpectedFailuresUseTheirCategories() {
        assertThatThrownBy(() -> run(executor, job(1, Map.of("durationMs", 50, "failureMode", "PERMANENT"))))
                .isInstanceOf(NonRetryableException.class);
        assertThatThrownBy(() -> run(executor, job(1, Map.of("durationMs", 50, "failureMode", "UNEXPECTED_ALWAYS"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureHappensAfterTheRequestedDelay() {
        long elapsed = timed(() -> {
            try {
                run(executor, job(1, Map.of("durationMs", 2000, "failureMode", "PERMANENT", "failAfterMs", 250)));
            } catch (NonRetryableException expected) {
                return;
            }
            throw new AssertionError("expected failure");
        });

        assertThat(elapsed).isBetween(230L, 1500L);
    }

    @Test
    void interruptionStopsWorkWithinOneSlice() throws Exception {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                run(executor, job(1, Map.of("durationMs", 60_000)));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        worker.start();
        Thread.sleep(100);

        long started = System.nanoTime();
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void randomDurationStaysInTheConfiguredRange() {
        SimulatedJobExecutor realRange = new SimulatedJobExecutor(new SimulationSpecParser(properties), properties);
        for (int i = 0; i < 1000; i++) {
            assertThat(realRange.randomDuration()).isBetween(Duration.ofMinutes(1), Duration.ofMinutes(5));
        }
    }

    @Test
    void payloadThatNoLongerValidatesIsAPermanentFailure() {
        SimulatedJobExecutor productionLike = new SimulatedJobExecutor(
                new SimulationSpecParser(TestProperties.create(false)), TestProperties.create(false));

        assertThatThrownBy(() -> run(productionLike, job(1, Map.of("failureMode", "PERMANENT"))))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Invalid job payload");
    }

    @Test
    void stopsAtTheNextCheckpointWhenToldTo() {
        java.util.concurrent.atomic.AtomicInteger checkpoints = new java.util.concurrent.atomic.AtomicInteger();
        ExecutionControl stopAfterThree = () -> {
            if (checkpoints.incrementAndGet() > 3) {
                throw new InterruptedException("stop requested");
            }
        };

        long elapsed = timed(() -> {
            try {
                executor.execute(job(1, Map.of("durationMs", 60_000)), stopAfterThree);
            } catch (InterruptedException expected) {
                return;
            }
            throw new AssertionError("expected the job to stop");
        });

        // Slices are 500 ms (TestProperties): three full slices, then the fourth checkpoint stops it.
        assertThat(checkpoints.get()).isEqualTo(4);
        assertThat(elapsed).isBetween(1400L, 3000L);
    }

    private static void run(SimulatedJobExecutor executor, ClaimedJob job) throws InterruptedException {
        executor.execute(job, ExecutionControl.UNCONTROLLED);
    }

    private static ClaimedJob job(int attempt, Map<String, Object> simulation) {
        return new ClaimedJob(UUID.randomUUID(), "worker-test", attempt, Map.of("simulation", simulation));
    }

    private static long timed(ThrowingRunnable runnable) {
        long start = System.nanoTime();
        try {
            runnable.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** The real parser, with a 10 ms – 60 s duration range so tests can run quickly. */
    private static final class ShortRangeParser extends SimulationSpecParser {
        ShortRangeParser(JobProcessorProperties base) {
            super(new JobProcessorProperties(base.worker(), base.dispatcher(), base.lease(), base.recovery(), base.retry(),
                    new JobProcessorProperties.Simulation(Duration.ofMillis(10), Duration.ofSeconds(60), true),
                    base.shutdown(), base.idempotency(), base.api(), base.security(), base.metrics()));
        }
    }
}
