package com.example.jobprocessor.worker.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.worker.execution.JobRunner;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import com.example.jobprocessor.worker.pool.WorkerPool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Stresses the real {@link JobDispatcher} and real {@link WorkerPool} together with tasks that
 * finish almost immediately, the situation where a freed permit races with its thread returning
 * to the executor queue.
 *
 * <p>Invariant checked on every claim: jobs claimed but not yet finished never exceed the pool
 * capacity. A claim counts as in flight from {@code claimNext} until its task body ends, which is
 * strictly inside the window where its permit is held, so any claim made without a free slot would
 * push the count above capacity. Rejections are detected through {@code releaseClaim}, the only
 * thing the dispatcher calls when the pool refuses a claimed job.
 */
class DispatcherWorkerPoolStressTest {

    /** Tight loops: many jobs to hit the permit/queue hand-off race as often as possible. */
    private static final int LOOP_JOBS = 20_000;
    /**
     * Real scheduler: each poll starts at most five instant jobs, and the OS timer decides the
     * poll rate (about 15.6 ms per tick on Windows), so throughput here is bounded by polling.
     */
    private static final int SCHEDULED_JOBS = 2_000;

    private final JobLifecycleService lifecycle = mock(JobLifecycleService.class);
    private final JobRunner runner = mock(JobRunner.class);
    private final WorkerIdentity identity = mock(WorkerIdentity.class);
    private final AtomicInteger remaining = new AtomicInteger();
    private int jobs;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger violations = new AtomicInteger();
    private final AtomicInteger finished = new AtomicInteger();
    private final AtomicInteger claimCalls = new AtomicInteger();

    private WorkerPool pool;

    @BeforeEach
    void setUp() {
        when(identity.workerId()).thenReturn("stress-worker");
        pool = new WorkerPool(TestProperties.create(false)); // 5 threads
        pool.start();

        when(lifecycle.claimNext(anyString())).thenAnswer(invocation -> {
            claimCalls.incrementAndGet();
            if (remaining.getAndDecrement() <= 0) {
                return Optional.empty();
            }
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            if (now > pool.capacity()) {
                violations.incrementAndGet();
            }
            return Optional.of(new ClaimedJob(UUID.randomUUID(), "stress-worker", 1, Map.of()));
        });
    }

    @AfterEach
    void tearDown() {
        pool.stop();
    }

    @Test
    void tightDispatchLoopWithInstantTasks() throws Exception {
        jobs(LOOP_JOBS);
        runnerFinishesAfter(0);
        JobDispatcher dispatcher = dispatcher(Duration.ofSeconds(1));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (finished.get() < jobs && System.nanoTime() < deadline) {
            dispatcher.dispatch();
        }

        assertInvariantsHeld();
    }

    @Test
    void concurrentDispatchCallsWithJitteredTasks() throws Exception {
        jobs(LOOP_JOBS);
        runnerFinishesAfter(200); // up to 200 microseconds of work
        JobDispatcher dispatcher = dispatcher(Duration.ofSeconds(1));
        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> loops = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                // Stronger than production, where a single scheduler thread calls dispatch().
                loops.add(callers.submit(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (finished.get() < jobs && System.nanoTime() < deadline) {
                        dispatcher.dispatch();
                    }
                }));
            }
            for (Future<?> loop : loops) {
                loop.get(90, TimeUnit.SECONDS);
            }
        } finally {
            callers.shutdownNow();
        }

        assertInvariantsHeld();
    }

    @Test
    void scheduledDispatcherWithMillisecondPolling() throws Exception {
        jobs(SCHEDULED_JOBS);
        runnerFinishesAfter(50);
        JobDispatcher dispatcher = dispatcher(Duration.ofMillis(1));

        long started = System.nanoTime();
        dispatcher.start();
        try {
            long deadline = started + TimeUnit.SECONDS.toNanos(60);
            while (finished.get() < jobs && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } finally {
            dispatcher.stop();
        }
        double millisPerJobBatch = Duration.ofNanos(System.nanoTime() - started).toMillis() / (finished.get() / 5.0);
        System.out.printf("Scheduled dispatcher: %d jobs, %.1f ms per 5-job poll cycle%n", finished.get(), millisPerJobBatch);

        assertInvariantsHeld();
    }

    /** Correctness first, so a throughput shortfall can never hide a slot violation. */
    private void assertInvariantsHeld() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.activeCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(violations.get()).as("claims made without a free worker slot").isZero();
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(pool.capacity());
        verify(lifecycle, never()).releaseClaim(any(), anyString()); // no rejection ever happened
        assertThat(pool.activeCount()).as("every permit was returned").isZero();
        assertThat(inFlight.get()).as("every claimed job finished").isZero();

        assertThat(maxInFlight.get()).as("the pool was saturated, so the race was exercised").isEqualTo(pool.capacity());
        assertThat(finished.get()).as("all jobs ran within the time budget").isEqualTo(jobs);
        // Each claim attempt beyond the jobs is an empty poll that also returned its permit.
        assertThat(claimCalls.get()).isGreaterThanOrEqualTo(jobs);
    }

    private void jobs(int count) {
        jobs = count;
        remaining.set(count);
    }

    private void runnerFinishesAfter(int maxMicros) {
        doAnswer(invocation -> {
            if (maxMicros > 0) {
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(ThreadLocalRandom.current().nextInt(maxMicros + 1)));
            }
            inFlight.decrementAndGet();
            finished.incrementAndGet();
            return null;
        }).when(runner).run(any(), anyLong());
    }

    private JobDispatcher dispatcher(Duration pollInterval) {
        JobProcessorProperties base = TestProperties.create(false);
        JobProcessorProperties properties = new JobProcessorProperties(base.worker(),
                new JobProcessorProperties.Dispatcher(true, pollInterval), base.lease(), base.recovery(), base.retry(),
                base.simulation(), base.shutdown(), base.idempotency(), base.api(), base.security());
        return new JobDispatcher(lifecycle, pool, runner, identity, properties);
    }
}
