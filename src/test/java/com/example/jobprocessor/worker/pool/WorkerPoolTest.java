package com.example.jobprocessor.worker.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerPoolTest {

    private WorkerPool pool = new WorkerPool(3, Duration.ofSeconds(5));

    @AfterEach
    void stop() {
        pool.stop();
    }

    @Test
    void slotsAreBoundedByThreadCount() {
        pool.start();

        assertThat(pool.tryAcquireSlot()).isTrue();
        assertThat(pool.tryAcquireSlot()).isTrue();
        assertThat(pool.tryAcquireSlot()).isTrue();
        assertThat(pool.tryAcquireSlot()).isFalse();
        assertThat(pool.activeCount()).isEqualTo(3);

        pool.releaseSlot();
        assertThat(pool.tryAcquireSlot()).isTrue();
    }

    @Test
    void taskRunsOnANamedWorkerThreadAndFreesItsSlot() throws Exception {
        pool.start();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();

        assertThat(pool.tryAcquireSlot()).isTrue();
        pool.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("job-worker-");
        waitUntil(() -> pool.activeCount() == 0);
    }

    @Test
    void neverRunsMoreTasksThanThreadsAndNeverRejectsWhenSlotsAreHeld() throws Exception {
        pool.start();
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(60);

        for (int i = 0; i < 60; i++) {
            while (!pool.tryAcquireSlot()) {
                Thread.onSpinWait();
            }
            // Submitting right as slots free up exercises the hand-off the queue capacity protects.
            pool.execute(() -> {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                finished.countDown();
            });
        }

        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(3);
        assertThat(maxConcurrent.get()).isGreaterThan(1);
    }

    @Test
    void shutdownLetsRunningTasksFinishWithinTheTimeout() throws Exception {
        pool.start();
        AtomicBoolean completed = new AtomicBoolean();
        pool.tryAcquireSlot();
        pool.execute(() -> {
            sleepQuietly(300);
            completed.set(true);
        });

        pool.stop();

        assertThat(completed).isTrue();
        assertThat(pool.isRunning()).isFalse();
    }

    @Test
    void shutdownInterruptsTasksThatOutliveTheTimeout() throws Exception {
        pool = new WorkerPool(2, Duration.ofMillis(200));
        pool.start();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        pool.tryAcquireSlot();
        pool.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        started.await(5, TimeUnit.SECONDS);

        long begin = System.nanoTime();
        pool.stop();

        assertThat(interrupted).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void stoppedPoolOffersNoSlotsAndRejectsWork() {
        pool.start();
        pool.stop();

        assertThat(pool.tryAcquireSlot()).isFalse();
        assertThatThrownBy(() -> pool.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void stopsAfterTheDispatcher() {
        assertThat(pool.getPhase()).isLessThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(10);
        }
    }
}
