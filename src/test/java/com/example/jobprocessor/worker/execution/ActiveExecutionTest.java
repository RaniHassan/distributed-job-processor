package com.example.jobprocessor.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.job.service.ClaimedJob;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ActiveExecutionTest {

    private final ActiveExecutions executions = new ActiveExecutions();
    private final ClaimedJob job = new ClaimedJob(UUID.randomUUID(), "worker-1", 1, Map.of());

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private ActiveExecution register(long deadlineFromNowMillis) {
        return executions.register(job, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineFromNowMillis));
    }

    @Test
    void stopInterruptsTheWorkerAndRecordsTheFirstReason() {
        ActiveExecution execution = register(30_000);

        assertThat(execution.requestStop(StopReason.CANCELLATION_REQUESTED)).isTrue();
        assertThat(execution.requestStop(StopReason.OWNERSHIP_LOST)).isFalse();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(execution.stopReason()).isEqualTo(StopReason.CANCELLATION_REQUESTED);
        assertThat(execution.isActive()).isFalse();
        assertThat(executions.active()).isEmpty();
    }

    @Test
    void finishedExecutionCannotBeInterrupted() {
        ActiveExecution execution = register(30_000);
        executions.unregister(execution);

        assertThat(execution.requestStop(StopReason.OWNERSHIP_LOST)).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).as("the thread's next job is safe").isFalse();
        assertThat(executions.size()).isZero();
    }

    @Test
    void checkpointPassesWhileLeaseIsValid() {
        ActiveExecution execution = register(30_000);

        assertThatCode(execution::checkpoint).doesNotThrowAnyException();
    }

    @Test
    void checkpointStopsAfterAStopRequest() {
        ActiveExecution execution = register(30_000);
        execution.requestStop(StopReason.OWNERSHIP_LOST);

        assertThatThrownBy(execution::checkpoint).isInstanceOf(InterruptedException.class).hasMessageContaining("OWNERSHIP_LOST");
    }

    @Test
    void checkpointSelfFencesOnceTheLocalLeaseDeadlinePasses() {
        ActiveExecution execution = register(-1);

        assertThatThrownBy(execution::checkpoint).isInstanceOf(InterruptedException.class).hasMessageContaining("LEASE_EXPIRED");
        assertThat(execution.stopReason()).isEqualTo(StopReason.LEASE_EXPIRED);
    }

    @Test
    void renewalMovesTheDeadlineForwardOnly() {
        long now = System.nanoTime();
        ActiveExecution execution = executions.register(job, now + 1_000);

        execution.leaseRenewed(now + 5_000);
        assertThat(execution.leaseDeadlinePassed(now + 4_000)).isFalse();

        execution.leaseRenewed(now + 2_000); // an older renewal arriving late must not shorten the lease
        assertThat(execution.leaseDeadlinePassed(now + 4_000)).isFalse();
        assertThat(execution.leaseDeadlinePassed(now + 5_001)).isTrue();
    }

    @Test
    void reRegisteringTheSameJobReplacesTheOldEntryOnly() {
        ActiveExecution first = register(30_000);
        ActiveExecution second = register(30_000);

        executions.unregister(first);

        assertThat(executions.active()).containsExactly(second);
    }
}
