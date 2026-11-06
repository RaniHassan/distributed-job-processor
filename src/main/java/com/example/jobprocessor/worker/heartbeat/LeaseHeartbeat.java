package com.example.jobprocessor.worker.heartbeat;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.common.util.FixedDelayLoop;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.LeaseStatus;
import com.example.jobprocessor.worker.execution.ActiveExecution;
import com.example.jobprocessor.worker.execution.ActiveExecutions;
import com.example.jobprocessor.worker.execution.StopReason;
import com.example.jobprocessor.worker.pool.WorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Renews the leases of this instance's running jobs (README §8.7, §11.2) from a dedicated thread,
 * independent of the worker threads doing long-running work.
 * <ul>
 *   <li>Renewed: the job's local lease deadline moves forward.</li>
 *   <li>Cancellation requested: the worker is stopped; the lease is not extended.</li>
 *   <li>Ownership lost: the worker is stopped and must not record a result.</li>
 *   <li>Renewal failed (e.g. database unreachable): the worker continues until its local lease
 *       deadline, then stops itself before Recovery may take the job over.</li>
 * </ul>
 * Stops after the worker pool, so leases stay alive while running jobs drain during shutdown.
 */
@Component
public class LeaseHeartbeat implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LeaseHeartbeat.class);

    /** Lower than {@link WorkerPool#PHASE}: starts before and stops after the pool. */
    public static final int PHASE = WorkerPool.PHASE - 256;

    private final ActiveExecutions executions;
    private final JobLifecycleService lifecycle;
    private final long leaseNanos;
    private final FixedDelayLoop loop;

    public LeaseHeartbeat(ActiveExecutions executions, JobLifecycleService lifecycle, JobProcessorProperties properties) {
        this.executions = executions;
        this.lifecycle = lifecycle;
        this.leaseNanos = properties.lease().duration().toNanos();
        this.loop = new FixedDelayLoop("job-heartbeat", properties.lease().heartbeatInterval(), this::heartbeat);
    }

    /** One heartbeat round for every active execution. */
    public void heartbeat() {
        for (ActiveExecution execution : executions.active()) {
            renew(execution);
            if (execution.leaseDeadlinePassed(System.nanoTime()) && execution.requestStop(StopReason.LEASE_EXPIRED)) {
                log.error("Lease could not be confirmed in time, worker stopped (self-fencing): jobId={} fencingToken={}",
                        execution.job().jobId(), execution.job().fencingToken());
            }
        }
    }

    private void renew(ActiveExecution execution) {
        long sentAt = System.nanoTime();
        LeaseStatus status;
        try {
            status = lifecycle.renewLease(execution.job());
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed, lease not confirmed: jobId={} reason={}", execution.job().jobId(), e.getMessage());
            return;
        }
        switch (status) {
            case RENEWED -> execution.leaseRenewed(sentAt + leaseNanos);
            case CANCEL_REQUESTED -> {
                if (execution.requestStop(StopReason.CANCELLATION_REQUESTED)) {
                    log.info("Cancellation detected by heartbeat, stopping worker: jobId={}", execution.job().jobId());
                }
            }
            case LOST -> {
                if (execution.requestStop(StopReason.OWNERSHIP_LOST)) {
                    log.warn("Lease lost, stopping worker without recording a result: jobId={} fencingToken={}",
                            execution.job().jobId(), execution.job().fencingToken());
                }
            }
        }
    }

    @Override
    public void start() {
        loop.start();
    }

    @Override
    public void stop() {
        loop.stop();
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
