package com.example.jobprocessor.monitoring.metrics;

import com.example.jobprocessor.job.service.RecordedOutcome;
import com.example.jobprocessor.job.service.RecoveredJob;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Job lifecycle metrics (README §22). Names follow Micrometer conventions and are exported in
 * Prometheus form as {@code jobs_submitted_total}, {@code job_processing_duration_seconds}, etc.
 * Counters are per instance: each event is counted once, by the instance where it happened, so
 * cluster totals are the sum over instances.
 */
@Component
public class JobMetrics {

    private final MeterRegistry registry;
    private final Counter created;
    private final Counter completed;
    private final Counter failed;
    private final Counter cancelled;
    private final Counter retried;
    private final Counter discarded;
    private final Timer queueWait;

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Not "jobs" + ".created": the Prometheus client reserves the "_created" suffix (OpenMetrics creation
        // timestamps) and would export it as "jobs_total". This exports as jobs_submitted_total.
        this.created = counter("jobs.submitted", "Jobs accepted through the API");
        this.completed = counter("jobs.completed", "Jobs that completed successfully");
        this.failed = counter("jobs.failed", "Jobs that ended FAILED (permanent failure or retries exhausted)");
        this.cancelled = counter("jobs.cancelled", "Jobs that ended CANCELLED");
        this.retried = counter("jobs.retried", "Retries scheduled after a failed or abandoned attempt");
        this.discarded = counter("job.results.discarded", "Worker results not recorded because ownership was lost");
        this.queueWait = Timer.builder("job.queue.wait.duration")
                .description("Time from a job becoming eligible to being claimed")
                .register(registry);
    }

    public void jobCreated() {
        afterCommit(created::increment);
    }

    /** A PENDING job cancelled directly by the API, before any worker claimed it. */
    public void jobCancelledBeforeStart() {
        afterCommit(cancelled::increment);
    }

    public void jobClaimed(Duration waitedForClaim) {
        afterCommit(() -> queueWait.record(waitedForClaim.isNegative() ? Duration.ZERO : waitedForClaim));
    }

    /** One worker result recorded through Job Management. */
    public void resultRecorded(RecordedOutcome outcome) {
        Counter counter = switch (outcome) {
            case COMPLETED -> completed;
            case RETRY_SCHEDULED -> retried;
            case FAILED -> failed;
            case CANCELLED -> cancelled;
            case OWNERSHIP_LOST -> discarded;
        };
        afterCommit(counter::increment);
    }

    /** Execution time of one attempt on a worker, tagged with how it ended. */
    public void attemptFinished(Duration duration, String outcome) {
        Timer.builder("job.processing.duration")
                .description("Execution time of one attempt on a worker")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    /** A job taken over by the Recovery Manager after its lease expired. */
    public void leaseExpired(RecoveredJob.Outcome outcome) {
        Counter expiration = Counter.builder("lease.expiration")
                .description("Jobs recovered after their worker's lease expired")
                .tag("outcome", outcome.name())
                .register(registry);
        Counter result = switch (outcome) {
            case REQUEUED -> retried;
            case FAILED -> failed;
            case CANCELLED -> cancelled;
        };
        afterCommit(() -> {
            expiration.increment();
            result.increment();
        });
    }

    /**
     * Counts a state change only once it is durable: inside a transaction the update is deferred
     * until commit (a rollback counts nothing); outside one it is applied immediately.
     */
    private static void afterCommit(Runnable update) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    update.run();
                }
            });
        } else {
            update.run();
        }
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }
}
