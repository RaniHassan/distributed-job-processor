package com.example.jobprocessor.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.job.lifecycle.FailureType;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

@ExtendWith(MockitoExtension.class)
class JobRunnerTest {

    @Mock
    private SimulatedJobExecutor executor;
    @Mock
    private JobLifecycleService lifecycle;
    @InjectMocks
    private JobRunner runner;

    private final ClaimedJob job = new ClaimedJob(UUID.randomUUID(), "worker-1", 1, Map.of());

    @Test
    void successIsRecordedAsCompletion() {
        when(lifecycle.complete(job)).thenReturn(true);

        runner.run(job);

        verify(lifecycle).complete(job);
        verify(lifecycle, never()).fail(any(), any(), any());
    }

    @Test
    void retryableFailureIsTransient() throws Exception {
        doThrow(new RetryableException("Upstream timed out")).when(executor).execute(job);

        runner.run(job);

        verify(lifecycle).fail(job, FailureType.TRANSIENT, "Upstream timed out");
        verify(lifecycle, never()).complete(any());
    }

    @Test
    void nonRetryableFailureIsPermanent() throws Exception {
        doThrow(new NonRetryableException("Bad input")).when(executor).execute(job);

        runner.run(job);

        verify(lifecycle).fail(job, FailureType.PERMANENT, "Bad input");
    }

    @Test
    void unexpectedFailureIsRecordedWithAGenericMessage() throws Exception {
        doThrow(new IllegalStateException("internal detail")).when(executor).execute(job);

        runner.run(job);

        verify(lifecycle).fail(job, FailureType.UNEXPECTED, "Unexpected processing error");
    }

    @Test
    void interruptionRecordsNothingAndKeepsTheInterruptFlag() throws Exception {
        doThrow(new InterruptedException()).when(executor).execute(job);

        runner.run(job);

        verifyNoInteractions(lifecycle);
        assertThat(Thread.interrupted()).isTrue(); // also clears the flag for other tests
    }

    @Test
    void lostOwnershipIsNotAnError() {
        when(lifecycle.complete(job)).thenReturn(false);

        assertThatCode(() -> runner.run(job)).doesNotThrowAnyException();
    }

    @Test
    void databaseFailureWhileRecordingIsContained() {
        when(lifecycle.complete(job)).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThatCode(() -> runner.run(job)).doesNotThrowAnyException();
    }

    @Test
    void databaseFailureWhileRecordingAFailureIsContained() throws Exception {
        doThrow(new NonRetryableException("Bad input")).when(executor).execute(job);
        when(lifecycle.fail(eq(job), any(), any())).thenThrow(new CannotGetJdbcConnectionException("down"));

        assertThatCode(() -> runner.run(job)).doesNotThrowAnyException();
    }
}
