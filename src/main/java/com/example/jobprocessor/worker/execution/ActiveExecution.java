package com.example.jobprocessor.worker.execution;

import com.example.jobprocessor.job.service.ClaimedJob;

/**
 * Local handle on one job running in this instance. Background threads (heartbeat, cancellation
 * watcher) use it to stop the worker; the worker uses it as its {@link ExecutionControl}.
 *
 * <p>Stopping interrupts the worker thread, but only until the worker marks the execution finished:
 * both happen under this object's lock, so a late stop request can never interrupt the next job
 * that the same pool thread picks up.
 *
 * <p>The local lease deadline is a conservative copy of {@code lease_until}: it is computed from a
 * moment just before the claim or renewal was sent, so it always falls before the database's
 * expiry. A worker past its deadline stops itself (self-fencing) before Recovery may take over.
 */
public final class ActiveExecution implements ExecutionControl {

    private final ClaimedJob job;
    private final Thread worker;
    private volatile long leaseDeadlineNanos;
    private StopReason stopReason;
    private boolean finished;

    ActiveExecution(ClaimedJob job, Thread worker, long leaseDeadlineNanos) {
        this.job = job;
        this.worker = worker;
        this.leaseDeadlineNanos = leaseDeadlineNanos;
    }

    public ClaimedJob job() {
        return job;
    }

    /**
     * Asks the worker to stop. The first reason wins.
     *
     * @return {@code true} if this call stopped the execution
     */
    public synchronized boolean requestStop(StopReason reason) {
        if (finished || stopReason != null) {
            return false;
        }
        stopReason = reason;
        worker.interrupt();
        return true;
    }

    /** Called by the worker when execution is over; later stop requests are ignored. */
    synchronized void markFinished() {
        finished = true;
    }

    public synchronized StopReason stopReason() {
        return stopReason;
    }

    /** Running and not asked to stop. */
    public synchronized boolean isActive() {
        return !finished && stopReason == null;
    }

    /** Records a confirmed renewal; the deadline only moves forward. */
    public void leaseRenewed(long newDeadlineNanos) {
        if (newDeadlineNanos - leaseDeadlineNanos > 0) {
            leaseDeadlineNanos = newDeadlineNanos;
        }
    }

    public boolean leaseDeadlinePassed(long nowNanos) {
        return nowNanos - leaseDeadlineNanos > 0;
    }

    @Override
    public void checkpoint() throws InterruptedException {
        if (leaseDeadlinePassed(System.nanoTime())) {
            requestStop(StopReason.LEASE_EXPIRED);
        }
        StopReason reason = stopReason();
        if (reason != null) {
            throw new InterruptedException("Execution stopped: " + reason);
        }
    }
}
