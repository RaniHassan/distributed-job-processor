package com.example.jobprocessor.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.job.lifecycle.FailureType;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.RecordedOutcome;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class JobRunnerTest {

    private final SimulatedJobExecutor executor = mock(SimulatedJobExecutor.class);
    private final JobLifecycleService lifecycle = mock(JobLifecycleService.class);
    private final ActiveExecutions executions = new ActiveExecutions();
    private JobRunner runner;

    private final ClaimedJob job = new ClaimedJob(UUID.randomUUID(), "worker-1", 1, Map.of());

    @BeforeEach
    void setUp() {
        runner = new JobRunner(executor, lifecycle, executions, TestProperties.create(false));
    }

    private void run() {
        runner.run(job, System.nanoTime());
    }

    @Test
    void successIsRecordedAsCompletion() {
        when(lifecycle.complete(job)).thenReturn(RecordedOutcome.COMPLETED);

        run();

        verify(lifecycle).complete(job);
        verify(lifecycle, never()).fail(any(), any(), any());
    }

    @Test
    void retryableFailureIsTransient() throws Exception {
        doThrow(new RetryableException("Upstream timed out")).when(executor).execute(eq(job), any());

        run();

        verify(lifecycle).fail(job, FailureType.TRANSIENT, "Upstream timed out");
        verify(lifecycle, never()).complete(any());
    }

    @Test
    void nonRetryableFailureIsPermanent() throws Exception {
        doThrow(new NonRetryableException("Bad input")).when(executor).execute(eq(job), any());

        run();

        verify(lifecycle).fail(job, FailureType.PERMANENT, "Bad input");
    }

    @Test
    void unexpectedFailureIsRecordedWithAGenericMessage() throws Exception {
        doThrow(new IllegalStateException("internal detail")).when(executor).execute(eq(job), any());

        run();

        verify(lifecycle).fail(job, FailureType.UNEXPECTED, "Unexpected processing error");
    }

    @Test
    void jobStoppedForCancellationConfirmsIt() throws Exception {
        doAnswer(invocation -> {
            executions.active().get(0).requestStop(StopReason.CANCELLATION_REQUESTED);
            throw new InterruptedException();
        }).when(executor).execute(eq(job), any());
        when(lifecycle.confirmCancelled(job)).thenReturn(RecordedOutcome.CANCELLED);

        run();

        verify(lifecycle).confirmCancelled(job);
        verify(lifecycle, never()).complete(any());
        verify(lifecycle, never()).fail(any(), any(), any());
    }

    @Test
    void jobStoppedForLostOwnershipRecordsNothing() throws Exception {
        doAnswer(invocation -> {
            executions.active().get(0).requestStop(StopReason.OWNERSHIP_LOST);
            throw new InterruptedException();
        }).when(executor).execute(eq(job), any());

        run();

        verifyNoInteractions(lifecycle);
    }

    @Test
    void stoppedJobRecordsNothingAndLeavesNoInterrupt() throws Exception {
        doThrow(new InterruptedException()).when(executor).execute(eq(job), any());

        run();

        verifyNoInteractions(lifecycle);
        assertThat(Thread.currentThread().isInterrupted()).as("pool thread is clean for its next job").isFalse();
    }

    @Test
    void jobIsRegisteredWhileRunningAndRemovedAfterwards() throws Exception {
        AtomicReference<ActiveExecution> seen = new AtomicReference<>();
        doAnswer(invocation -> {
            seen.set(executions.active().get(0));
            return null;
        }).when(executor).execute(eq(job), any());

        run();

        assertThat(seen.get().job()).isEqualTo(job);
        assertThat(executions.size()).isZero();
        assertThat(seen.get().requestStop(StopReason.OWNERSHIP_LOST)).as("finished executions cannot be stopped").isFalse();
    }

    @Test
    void executorIsControlledByTheRegisteredExecution() throws Exception {
        doAnswer(invocation -> {
            ExecutionControl control = invocation.getArgument(1);
            assertThat(control).isSameAs(executions.active().get(0));
            return null;
        }).when(executor).execute(eq(job), any());

        run();

        verify(lifecycle).complete(job);
    }

    @Test
    void lateStopRequestDoesNotPreventRecordingTheResult() throws Exception {
        doAnswer(invocation -> {
            // Work is done; a stop request arrives just before the runner records the result.
            executions.active().get(0).requestStop(StopReason.OWNERSHIP_LOST);
            return null;
        }).when(executor).execute(eq(job), any());
        when(lifecycle.complete(job)).thenAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).as("interrupt cleared before recording").isFalse();
            return RecordedOutcome.OWNERSHIP_LOST; // the fenced update rejects it, as it would after a real ownership loss
        });

        run();

        verify(lifecycle).complete(job);
    }

    @Test
    void lostOwnershipIsNotAnError() {
        when(lifecycle.complete(job)).thenReturn(RecordedOutcome.OWNERSHIP_LOST);

        assertThatCode(this::run).doesNotThrowAnyException();
    }

    @Test
    void databaseFailureWhileRecordingIsContained() {
        when(lifecycle.complete(job)).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThatCode(this::run).doesNotThrowAnyException();
        assertThat(executions.size()).isZero();
    }

    @Test
    void databaseFailureWhileRecordingAFailureIsContained() throws Exception {
        doThrow(new NonRetryableException("Bad input")).when(executor).execute(eq(job), any());
        when(lifecycle.fail(eq(job), any(), any())).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThatCode(this::run).doesNotThrowAnyException();
    }
}
