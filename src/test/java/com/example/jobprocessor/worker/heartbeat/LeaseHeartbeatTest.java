package com.example.jobprocessor.worker.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.LeaseStatus;
import com.example.jobprocessor.worker.execution.ActiveExecution;
import com.example.jobprocessor.worker.execution.ActiveExecutions;
import com.example.jobprocessor.worker.execution.StopReason;
import com.example.jobprocessor.worker.pool.WorkerPool;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Lease 30s in {@link TestProperties}. Executions are registered on the test thread. */
class LeaseHeartbeatTest {

    private final JobLifecycleService lifecycle = mock(JobLifecycleService.class);
    private final ActiveExecutions executions = new ActiveExecutions();
    private final LeaseHeartbeat heartbeat = new LeaseHeartbeat(executions, lifecycle, TestProperties.create(false));
    private final ClaimedJob job = new ClaimedJob(UUID.randomUUID(), "worker-1", 1, Map.of());

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private ActiveExecution register(long deadlineFromNowMillis) {
        return executions.register(job, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineFromNowMillis));
    }

    @Test
    void renewalExtendsTheLocalDeadline() {
        ActiveExecution execution = register(1_000);
        when(lifecycle.renewLease(job)).thenReturn(LeaseStatus.RENEWED);

        heartbeat.heartbeat();

        assertThat(execution.isActive()).isTrue();
        assertThat(execution.leaseDeadlinePassed(System.nanoTime() + TimeUnit.SECONDS.toNanos(25))).isFalse();
    }

    @Test
    void lostOwnershipStopsTheWorker() {
        ActiveExecution execution = register(30_000);
        when(lifecycle.renewLease(job)).thenReturn(LeaseStatus.LOST);

        heartbeat.heartbeat();

        assertThat(execution.stopReason()).isEqualTo(StopReason.OWNERSHIP_LOST);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void cancellationStopsTheWorker() {
        ActiveExecution execution = register(30_000);
        when(lifecycle.renewLease(job)).thenReturn(LeaseStatus.CANCEL_REQUESTED);

        heartbeat.heartbeat();

        assertThat(execution.stopReason()).isEqualTo(StopReason.CANCELLATION_REQUESTED);
    }

    @Test
    void failedRenewalKeepsWorkingWhileTheLocalLeaseIsValid() {
        ActiveExecution execution = register(30_000);
        when(lifecycle.renewLease(job)).thenThrow(new CannotGetJdbcConnectionException("down"));

        heartbeat.heartbeat();

        assertThat(execution.isActive()).isTrue();
    }

    @Test
    void failedRenewalPastTheLocalDeadlineSelfFences() {
        ActiveExecution execution = register(-1);
        when(lifecycle.renewLease(job)).thenThrow(new CannotGetJdbcConnectionException("down"));

        heartbeat.heartbeat();

        assertThat(execution.stopReason()).isEqualTo(StopReason.LEASE_EXPIRED);
    }

    @Test
    void renewalJustInTimeAvoidsSelfFencing() {
        ActiveExecution execution = register(-1);
        when(lifecycle.renewLease(job)).thenReturn(LeaseStatus.RENEWED);

        heartbeat.heartbeat();

        assertThat(execution.isActive()).isTrue();
    }

    @Test
    void stoppedOrFinishedExecutionsAreNotRenewed() {
        ActiveExecution stopped = register(30_000);
        stopped.requestStop(StopReason.CANCELLATION_REQUESTED);

        heartbeat.heartbeat();

        verify(lifecycle, never()).renewLease(any());
    }

    @Test
    void stopsAfterTheWorkerPoolSoDrainingJobsKeepTheirLeases() {
        assertThat(heartbeat.getPhase()).isLessThan(WorkerPool.PHASE);
    }
}
