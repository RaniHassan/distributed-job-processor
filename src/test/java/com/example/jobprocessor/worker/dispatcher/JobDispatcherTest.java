package com.example.jobprocessor.worker.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.worker.execution.JobRunner;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import com.example.jobprocessor.worker.pool.WorkerPool;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class JobDispatcherTest {

    private final JobLifecycleService lifecycle = mock(JobLifecycleService.class);
    private final WorkerPool pool = mock(WorkerPool.class);
    private final JobRunner runner = mock(JobRunner.class);
    private final WorkerIdentity identity = mock(WorkerIdentity.class);
    private JobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(identity.workerId()).thenReturn("worker-1");
        dispatcher = new JobDispatcher(lifecycle, pool, runner, identity, TestProperties.create(false));
    }

    private static ClaimedJob job() {
        return new ClaimedJob(UUID.randomUUID(), "worker-1", 1, Map.of());
    }

    @Test
    void claimsOnlyWhileWorkerSlotsAreFree() {
        when(pool.tryAcquireSlot()).thenReturn(true, true, false);
        when(lifecycle.claimNext("worker-1")).thenReturn(Optional.of(job()), Optional.of(job()));

        assertThat(dispatcher.dispatch()).isEqualTo(2);

        verify(lifecycle, times(2)).claimNext("worker-1");
        verify(pool, times(2)).execute(any());
        verify(pool, never()).releaseSlot();
    }

    @Test
    void neverClaimsWithoutAFreeSlot() {
        when(pool.tryAcquireSlot()).thenReturn(false);

        assertThat(dispatcher.dispatch()).isZero();

        verifyNoInteractions(lifecycle);
    }

    @Test
    void returnsTheSlotWhenNoJobIsEligible() {
        when(pool.tryAcquireSlot()).thenReturn(true);
        when(lifecycle.claimNext(anyString())).thenReturn(Optional.empty());

        assertThat(dispatcher.dispatch()).isZero();

        verify(pool).releaseSlot();
        verify(lifecycle, times(1)).claimNext(anyString());
    }

    @Test
    void returnsTheSlotAndBacksOffWhenTheDatabaseFails() {
        when(pool.tryAcquireSlot()).thenReturn(true);
        when(lifecycle.claimNext(anyString())).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThat(dispatcher.dispatch()).isZero();

        verify(pool).releaseSlot();
        verify(lifecycle, times(1)).claimNext(anyString());
    }

    @Test
    void rejectedJobIsReleasedBackToTheQueue() {
        ClaimedJob claimed = job();
        when(pool.tryAcquireSlot()).thenReturn(true, false);
        when(lifecycle.claimNext(anyString())).thenReturn(Optional.of(claimed));
        doThrow(new RejectedExecutionException("full")).when(pool).execute(any());

        assertThat(dispatcher.dispatch()).isZero();

        verify(pool).releaseSlot();
        verify(lifecycle).releaseClaim(eq(claimed), anyString());
    }

    @Test
    void disabledDispatcherNeverStarts() {
        JobProcessorProperties base = TestProperties.create(false);
        JobProcessorProperties disabled = new JobProcessorProperties(base.worker(),
                new JobProcessorProperties.Dispatcher(false, Duration.ofMillis(10)), base.lease(), base.recovery(),
                base.retry(), base.simulation(), base.shutdown(), base.idempotency(), base.api(), base.security());
        JobDispatcher off = new JobDispatcher(lifecycle, pool, runner, identity, disabled);

        off.start();

        assertThat(off.isRunning()).isFalse();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void stopsBeforeTheWorkerPool() {
        assertThat(dispatcher.getPhase()).isGreaterThan(WorkerPool.PHASE);
    }
}
