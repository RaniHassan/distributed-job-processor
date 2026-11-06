package com.example.jobprocessor.recovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.RecoveredJob;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class RecoveryManagerTest {

    private final JobLifecycleService lifecycle = mock(JobLifecycleService.class);
    private final RecoveryManager manager = new RecoveryManager(lifecycle);

    private static Optional<RecoveredJob> recovered() {
        return Optional.of(new RecoveredJob(UUID.randomUUID(), "w", 1, RecoveredJob.Outcome.REQUEUED, 1, null));
    }

    @Test
    void recoversUntilNoExpiredLeaseIsLeft() {
        when(lifecycle.recoverNextExpiredLease()).thenReturn(recovered(), recovered(), Optional.empty());

        assertThat(manager.recoverExpiredLeases()).hasSize(2);
        verify(lifecycle, times(3)).recoverNextExpiredLease();
    }

    @Test
    void boundsTheWorkPerRun() {
        when(lifecycle.recoverNextExpiredLease()).thenAnswer(invocation -> recovered());

        assertThat(manager.recoverExpiredLeases()).hasSize(RecoveryManager.MAX_JOBS_PER_RUN);
    }

    @Test
    void databaseFailureEndsTheRunWithoutThrowing() {
        when(lifecycle.recoverNextExpiredLease()).thenReturn(recovered()).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThat(manager.recoverExpiredLeases()).hasSize(1);
    }
}
