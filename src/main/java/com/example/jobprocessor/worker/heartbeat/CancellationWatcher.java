package com.example.jobprocessor.worker.heartbeat;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.common.util.FixedDelayLoop;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.worker.execution.ActiveExecution;
import com.example.jobprocessor.worker.execution.ActiveExecutions;
import com.example.jobprocessor.worker.execution.StopReason;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Detects cancellation requests for this instance's running jobs (README §9.1). The request may
 * have been made on any instance, so it is read from PostgreSQL: one ownership-aware query per
 * interval covers all local jobs, rather than one query per worker.
 */
@Component
public class CancellationWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CancellationWatcher.class);

    private final ActiveExecutions executions;
    private final JobLifecycleService lifecycle;
    private final WorkerIdentity identity;
    private final FixedDelayLoop loop;

    public CancellationWatcher(ActiveExecutions executions, JobLifecycleService lifecycle, WorkerIdentity identity,
                               JobProcessorProperties properties) {
        this.executions = executions;
        this.lifecycle = lifecycle;
        this.identity = identity;
        this.loop = new FixedDelayLoop("job-cancellation-watch", properties.worker().cancellationCheckInterval(), this::check);
    }

    public void check() {
        List<ActiveExecution> active = executions.active();
        if (active.isEmpty()) {
            return;
        }
        Set<UUID> requested;
        try {
            requested = lifecycle.findCancellationRequests(identity.workerId(),
                    active.stream().map(ActiveExecution::job).toList());
        } catch (RuntimeException e) {
            log.warn("Cancellation check failed: {}", e.getMessage());
            return;
        }
        for (ActiveExecution execution : active) {
            ClaimedJob job = execution.job();
            if (requested.contains(job.jobId()) && execution.requestStop(StopReason.CANCELLATION_REQUESTED)) {
                log.info("Cancellation requested, stopping worker: jobId={} fencingToken={}", job.jobId(), job.fencingToken());
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
        return LeaseHeartbeat.PHASE;
    }
}
