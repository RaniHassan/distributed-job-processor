package com.example.jobprocessor.worker.execution;

import com.example.jobprocessor.job.lifecycle.FailureType;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one claimed job on a worker thread and reports the outcome through Job Management.
 * <ul>
 *   <li>Success: {@code complete}. Failure: {@code fail} with its classification.</li>
 *   <li>Interruption: nothing is recorded; the outcome is unknown, so the job stays with its
 *       lease until Recovery handles it (at-least-once execution).</li>
 *   <li>If the result cannot be stored (ownership lost, database down), it is dropped and logged;
 *       the database state stays authoritative.</li>
 * </ul>
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final SimulatedJobExecutor executor;
    private final JobLifecycleService lifecycle;

    public JobRunner(SimulatedJobExecutor executor, JobLifecycleService lifecycle) {
        this.executor = executor;
        this.lifecycle = lifecycle;
    }

    public void run(ClaimedJob job) {
        long started = System.nanoTime();
        log.info("Job started: jobId={} attempt={} workerId={}", job.jobId(), job.attemptNumber(), job.workerId());
        try {
            executor.execute(job);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Job interrupted, outcome not recorded; its lease will expire: jobId={} attempt={}",
                    job.jobId(), job.attemptNumber());
            return;
        } catch (Exception e) {
            FailureType type = FailureClassifier.classify(e);
            if (type == FailureType.UNEXPECTED) {
                log.error("Job failed unexpectedly: jobId={} attempt={}", job.jobId(), job.attemptNumber(), e);
            } else {
                log.warn("Job failed ({}): jobId={} attempt={} reason={}", type, job.jobId(), job.attemptNumber(), e.getMessage());
            }
            record(job, "failure", () -> lifecycle.fail(job, type, FailureClassifier.clientMessage(type, e)));
            return;
        }
        log.info("Job execution finished: jobId={} attempt={} took={}ms", job.jobId(), job.attemptNumber(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        record(job, "completion", () -> lifecycle.complete(job));
    }

    private static void record(ClaimedJob job, String outcome, BooleanSupplier operation) {
        try {
            if (!operation.getAsBoolean()) {
                log.warn("Job {} discarded, ownership was lost: jobId={} fencingToken={}", outcome, job.jobId(), job.fencingToken());
            }
        } catch (RuntimeException e) {
            log.error("Could not record job {}; it stays RUNNING until its lease expires: jobId={} reason={}",
                    outcome, job.jobId(), e.getMessage());
        }
    }
}
