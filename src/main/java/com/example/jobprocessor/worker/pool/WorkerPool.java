package com.example.jobprocessor.worker.pool;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Bounded worker pool (README §8.6): a fixed number of threads plus a semaphore with one permit
 * per thread. The Dispatcher must hold a permit before it claims a job, so a job is only claimed
 * when a thread is free and claimed work never waits in memory.
 *
 * <p>The executor's queue has the same capacity as the pool. It is effectively unused: the permits
 * already cap tasks at the thread count, and the queue only absorbs the moment between a task
 * releasing its permit and its thread taking the next task (with a zero-capacity queue that moment
 * would cause spurious rejections). {@link ThreadPoolExecutor.AbortPolicy} makes any rejection
 * explicit to the caller.
 *
 * <p>Shutdown (README §14): new work is refused, running jobs get the configured timeout to finish,
 * then remaining threads are interrupted. Interrupted jobs record nothing; their leases expire and
 * Recovery takes over.
 */
@Component
public class WorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    /** Stops after the Dispatcher (which stops at the default phase) and before the web server. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    private final int threads;
    private final Duration shutdownTimeout;
    private final Semaphore slots;
    private volatile ThreadPoolExecutor executor;
    private volatile boolean running;

    @Autowired
    public WorkerPool(JobProcessorProperties properties) {
        this(properties.worker().threads(), properties.shutdown().timeout());
    }

    WorkerPool(int threads, Duration shutdownTimeout) {
        this.threads = threads;
        this.shutdownTimeout = shutdownTimeout;
        this.slots = new Semaphore(threads);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads), new WorkerThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        running = true;
        log.info("Worker pool started with {} threads", threads);
    }

    /** Reserves a worker thread for a job about to be claimed; {@code false} if none is free. */
    public boolean tryAcquireSlot() {
        return running && slots.tryAcquire();
    }

    public void releaseSlot() {
        slots.release();
    }

    /**
     * Runs a task in a slot already reserved with {@link #tryAcquireSlot()}; the slot is released
     * when the task ends. If the task is rejected, the slot is still held and the caller must
     * release it.
     */
    public void execute(Runnable task) {
        ThreadPoolExecutor current = executor;
        if (!running || current == null) {
            throw new RejectedExecutionException("Worker pool is not running");
        }
        current.execute(() -> {
            try {
                task.run();
            } finally {
                slots.release();
            }
        });
    }

    public int activeCount() {
        return threads - slots.availablePermits();
    }

    public int capacity() {
        return threads;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Worker pool did not drain within {}; interrupting {} running job(s), their leases will expire",
                        shutdownTimeout, executor.getActiveCount());
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Worker threads did not stop after interruption");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker pool stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private static final class WorkerThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "job-worker-" + counter.incrementAndGet());
            thread.setUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception in {}", t.getName(), e));
            return thread;
        }
    }
}
