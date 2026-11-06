package com.example.jobprocessor.worker.heartbeat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.worker.execution.ActiveExecution;
import com.example.jobprocessor.worker.execution.ActiveExecutions;
import com.example.jobprocessor.worker.execution.StopReason;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class CancellationWatcherTest {

    private final JobLifecycleService lifecycle = mock(JobLifecycleService.class);
    private final WorkerIdentity identity = mock(WorkerIdentity.class);
    private final ActiveExecutions executions = new ActiveExecutions();
    private final CancellationWatcher watcher =
            new CancellationWatcher(executions, lifecycle, identity, TestProperties.create(false));

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private ActiveExecution register() {
        return executions.register(new ClaimedJob(UUID.randomUUID(), "worker-1", 1, Map.of()), System.nanoTime() + 1_000_000_000L);
    }

    @Test
    void stopsOnlyTheJobsWithACancellationRequest() {
        when(identity.workerId()).thenReturn("worker-1");
        ActiveExecution cancelled = register();
        ActiveExecution untouched = register();
        when(lifecycle.findCancellationRequests(eq("worker-1"), any())).thenReturn(Set.of(cancelled.job().jobId()));

        watcher.check();

        assertThat(cancelled.stopReason()).isEqualTo(StopReason.CANCELLATION_REQUESTED);
        assertThat(untouched.isActive()).isTrue();
    }

    @Test
    void doesNotQueryWhenNothingRuns() {
        watcher.check();

        verifyNoInteractions(lifecycle);
    }

    @Test
    void databaseErrorsDoNotStopWorkers() {
        when(identity.workerId()).thenReturn("worker-1");
        ActiveExecution execution = register();
        when(lifecycle.findCancellationRequests(anyString(), any())).thenThrow(new CannotGetJdbcConnectionException("down"));

        watcher.check();

        assertThat(execution.isActive()).isTrue();
    }
}
