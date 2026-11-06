package com.example.jobprocessor.monitoring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.job.service.RecordedOutcome;
import com.example.jobprocessor.job.service.RecoveredJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JobMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final JobMetrics metrics = new JobMetrics(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void workerResultsMapToTheLifecycleCounters() {
        metrics.resultRecorded(RecordedOutcome.COMPLETED);
        metrics.resultRecorded(RecordedOutcome.RETRY_SCHEDULED);
        metrics.resultRecorded(RecordedOutcome.FAILED);
        metrics.resultRecorded(RecordedOutcome.CANCELLED);
        metrics.resultRecorded(RecordedOutcome.OWNERSHIP_LOST);
        metrics.jobCancelledBeforeStart();

        assertThat(count("jobs.completed")).isEqualTo(1);
        assertThat(count("jobs.retried")).isEqualTo(1);
        assertThat(count("jobs.failed")).isEqualTo(1);
        assertThat(count("jobs.cancelled")).isEqualTo(2);
        assertThat(count("job.results.discarded")).isEqualTo(1);
    }

    @Test
    void recoveriesCountAsLeaseExpirationsAndAsTheirOutcome() {
        metrics.leaseExpired(RecoveredJob.Outcome.REQUEUED);
        metrics.leaseExpired(RecoveredJob.Outcome.FAILED);
        metrics.leaseExpired(RecoveredJob.Outcome.CANCELLED);

        assertThat(registry.get("lease.expiration").counters()).hasSize(3);
        assertThat(registry.get("lease.expiration").tag("outcome", "REQUEUED").counter().count()).isEqualTo(1);
        assertThat(count("jobs.retried")).isEqualTo(1);
        assertThat(count("jobs.failed")).isEqualTo(1);
        assertThat(count("jobs.cancelled")).isEqualTo(1);
    }

    @Test
    void queueWaitIsNeverNegative() {
        metrics.jobClaimed(Duration.ofMillis(-5)); // clock rounding at claim time
        metrics.jobClaimed(Duration.ofMillis(300));

        assertThat(registry.get("job.queue.wait.duration").timer().count()).isEqualTo(2);
        assertThat(registry.get("job.queue.wait.duration").timer().max(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(300);
    }

    @Test
    void insideATransactionNothingIsCountedUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.jobCreated();
        metrics.resultRecorded(RecordedOutcome.COMPLETED);
        assertThat(count("jobs.submitted")).as("not before commit").isZero();

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        assertThat(count("jobs.submitted")).isEqualTo(1);
        assertThat(count("jobs.completed")).isEqualTo(1);
    }

    @Test
    void rolledBackChangesAreNeverCounted() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.resultRecorded(RecordedOutcome.FAILED);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(count("jobs.failed")).isZero();
    }
}
