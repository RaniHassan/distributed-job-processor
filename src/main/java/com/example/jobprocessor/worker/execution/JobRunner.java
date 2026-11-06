package com.example.jobprocessor.worker.execution;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.lifecycle.FailureType;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.RecordedOutcome;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one claimed job on a worker thread and reports the outcome through Job Management.
 * <ul>
 *   <li>Success: {@code complete}. Failure: {@code fail} with its classification, which decides
 *       between retry and final failure. A pending cancellation request wins over both.</li>
 *   <li>Stopped for cancellation: {@code confirmCancelled}.</li>
 *   <li>Stopped for lost ownership or the lease deadline, or interrupted by shutdown: nothing is
 *       recorded; the outcome is unknown, so the job stays with its lease until Recovery handles
 *       it (at-least-once execution).</li>
 *   <li>If the result cannot be stored (ownership lost, database down), it is dropped and logged;
 *       the database state stays authoritative.</li>
 * </ul>
 * While running, the job is registered in {@link ActiveExecutions} so its lease is renewed and
 * it can be stopped.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final SimulatedJobExecutor executor;
    private final JobLifecycleService lifecycle;
    private final ActiveExecutions executions;
    private final long leaseNanos;

    public JobRunner(SimulatedJobExecutor executor, JobLifecycleService lifecycle, ActiveExecutions executions,
                     JobProcessorProperties properties) {
        this.executor = executor;
        this.lifecycle = lifecycle;
        this.executions = executions;
        this.leaseNanos = properties.lease().duration().toNanos();
    }

    /**
     * @param leaseStartNanos {@link System#nanoTime()} taken just before the claim was sent; the
     *                        local lease deadline is measured from it
     */
    public void run(ClaimedJob job, long leaseStartNanos) {
        ActiveExecution execution = executions.register(job, leaseStartNanos + leaseNanos);
        try {
            execute(job, execution);
        } finally {
            executions.unregister(execution);
            Thread.interrupted(); // never leak an interrupt into the pool thread's next job
        }
    }

    private void execute(ClaimedJob job, ActiveExecution execution) {
        long started = System.nanoTime();
        log.info("Job started: jobId={} attempt={} workerId={}", job.jobId(), job.attemptNumber(), job.workerId());
        try {
            executor.execute(job, execution);
        } catch (InterruptedException e) {
            finishExecution(execution);
            StopReason reason = execution.stopReason();
            if (reason == StopReason.CANCELLATION_REQUESTED) {
                // Cooperative cancellation (README §9.1): the worker stopped on purpose and says so.
                log.info("Job stopped for cancellation: jobId={} attempt={}", job.jobId(), job.attemptNumber());
                record(job, "cancellation", () -> lifecycle.confirmCancelled(job));
                return;
            }
            log.warn("Job stopped before finishing, outcome not recorded: jobId={} attempt={} reason={}",
                    job.jobId(), job.attemptNumber(), reason != null ? reason : "SHUTDOWN");
            return;
        } catch (Exception e) {
            finishExecution(execution);
            FailureType type = FailureClassifier.classify(e);
            if (type == FailureType.UNEXPECTED) {
                log.error("Job failed unexpectedly: jobId={} attempt={}", job.jobId(), job.attemptNumber(), e);
            } else {
                log.warn("Job failed ({}): jobId={} attempt={} reason={}", type, job.jobId(), job.attemptNumber(), e.getMessage());
            }
            record(job, "failure", () -> lifecycle.fail(job, type, FailureClassifier.clientMessage(type, e)));
            return;
        }
        finishExecution(execution);
        log.info("Job execution finished: jobId={} attempt={} took={}ms", job.jobId(), job.attemptNumber(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        record(job, "completion", () -> lifecycle.complete(job));
    }

    /**
     * Ends the window in which background threads may interrupt this worker, then clears any
     * interrupt that arrived meanwhile, so recording the result is not disturbed. Whether the
     * result is still accepted is decided by the fenced update, not by local state.
     */
    private static void finishExecution(ActiveExecution execution) {
        execution.markFinished();
        Thread.interrupted();
    }

    private static void record(ClaimedJob job, String result, Supplier<RecordedOutcome> operation) {
        try {
            RecordedOutcome outcome = operation.get();
            if (outcome == RecordedOutcome.OWNERSHIP_LOST) {
                log.warn("Job {} discarded, ownership was lost: jobId={} fencingToken={}", result, job.jobId(), job.fencingToken());
            } else {
                log.info("Job {} recorded as {}: jobId={} attempt={}", result, outcome, job.jobId(), job.attemptNumber());
            }
        } catch (RuntimeException e) {
            log.error("Could not record job {}; it stays owned until its lease expires: jobId={} reason={}",
                    result, job.jobId(), e.getMessage());
        }
    }
}
