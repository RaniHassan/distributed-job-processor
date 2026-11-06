package com.example.jobprocessor.worker.dispatcher;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.worker.execution.JobRunner;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import com.example.jobprocessor.worker.pool.WorkerPool;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Moves jobs from PostgreSQL to the local worker pool (README §8.5). On every poll it claims jobs
 * one at a time while a worker slot is free, so it never claims more than it can run and never
 * buffers claimed work in memory. It executes no business logic itself.
 */
@Component
public class JobDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final JobLifecycleService lifecycle;
    private final WorkerPool pool;
    private final JobRunner runner;
    private final WorkerIdentity identity;
    private final boolean enabled;
    private final Duration pollInterval;

    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;

    public JobDispatcher(JobLifecycleService lifecycle, WorkerPool pool, JobRunner runner, WorkerIdentity identity,
                         JobProcessorProperties properties) {
        this.lifecycle = lifecycle;
        this.pool = pool;
        this.runner = runner;
        this.identity = identity;
        this.enabled = properties.dispatcher().enabled();
        this.pollInterval = properties.dispatcher().pollInterval();
    }

    @Override
    public synchronized void start() {
        if (!enabled) {
            log.info("Job dispatcher disabled; this instance serves the API only");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "job-dispatcher"));
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("Job dispatcher started: workerId={} pollInterval={}", identity.workerId(), pollInterval);
    }

    /** Stops claiming; a poll in progress finishes first. Runs before the worker pool drains. */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Job dispatcher stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE;
    }

    private void pollSafely() {
        try {
            dispatch();
        } catch (RuntimeException e) {
            // Never let an exception cancel the scheduled task.
            log.error("Dispatch cycle failed", e);
        }
    }

    /** Claims and starts jobs while worker slots are free. Returns the number of jobs started. */
    int dispatch() {
        int started = 0;
        while (pool.tryAcquireSlot()) {
            Optional<ClaimedJob> claimed;
            try {
                claimed = lifecycle.claimNext(identity.workerId());
            } catch (RuntimeException e) {
                pool.releaseSlot();
                log.warn("Claiming failed, retrying next poll: {}", e.getMessage());
                break;
            }
            if (claimed.isEmpty()) {
                pool.releaseSlot();
                break;
            }
            if (submit(claimed.get())) {
                started++;
            }
        }
        return started;
    }

    private boolean submit(ClaimedJob job) {
        try {
            pool.execute(() -> runner.run(job));
            return true;
        } catch (RejectedExecutionException e) {
            pool.releaseSlot();
            log.error("Worker pool rejected claimed job, releasing it: jobId={}", job.jobId());
            try {
                lifecycle.releaseClaim(job, "Released by " + job.workerId() + ": worker pool rejected the job");
            } catch (RuntimeException releaseFailure) {
                log.error("Could not release job, its lease will expire: jobId={} reason={}",
                        job.jobId(), releaseFailure.getMessage());
            }
            return false;
        }
    }
}
