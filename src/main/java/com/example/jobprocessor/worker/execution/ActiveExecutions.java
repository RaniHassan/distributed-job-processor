package com.example.jobprocessor.worker.execution;

import com.example.jobprocessor.job.service.ClaimedJob;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The jobs currently executing in this instance. Local execution state only (README §6.1): it
 * says which leases this process should renew and which threads to stop. It is never consulted
 * for decisions about ownership, which always come from PostgreSQL.
 */
@Component
public class ActiveExecutions {

    private final Map<java.util.UUID, ActiveExecution> executions = new ConcurrentHashMap<>();

    /** Registers the calling thread as the worker executing {@code job}. */
    public ActiveExecution register(ClaimedJob job, long leaseDeadlineNanos) {
        ActiveExecution execution = new ActiveExecution(job, Thread.currentThread(), leaseDeadlineNanos);
        executions.put(job.jobId(), execution);
        return execution;
    }

    public void unregister(ActiveExecution execution) {
        execution.markFinished();
        executions.remove(execution.job().jobId(), execution);
    }

    /** Executions still running and not asked to stop. */
    public List<ActiveExecution> active() {
        return executions.values().stream().filter(ActiveExecution::isActive).toList();
    }

    public int size() {
        return executions.size();
    }
}
