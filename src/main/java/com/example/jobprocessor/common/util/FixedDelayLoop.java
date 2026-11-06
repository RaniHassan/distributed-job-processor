package com.example.jobprocessor.common.util;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a task repeatedly on its own named thread with a fixed delay between runs. Exceptions are
 * logged and never cancel the schedule. Background loops use separate threads so that, for
 * example, heartbeats keep running while workers are busy or the database is slow.
 */
public final class FixedDelayLoop {

    private static final Logger log = LoggerFactory.getLogger(FixedDelayLoop.class);

    private final String name;
    private final Duration delay;
    private final Runnable task;
    private ScheduledExecutorService scheduler;

    public FixedDelayLoop(String name, Duration delay, Runnable task) {
        this.name = name;
        this.delay = delay;
        this.task = task;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, name));
        scheduler.scheduleWithFixedDelay(this::runSafely, 0, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops scheduling and waits for a run in progress to finish. */
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    private void runSafely() {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("{} run failed", name, e);
        }
    }
}
