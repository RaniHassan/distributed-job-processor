package com.example.jobprocessor.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.job.domain.Job;
import com.example.jobprocessor.job.domain.JobStatus;
import com.example.jobprocessor.job.dto.CreateJobRequest;
import com.example.jobprocessor.job.idempotency.IdempotencyKeyConflictException;
import com.example.jobprocessor.job.idempotency.IdempotentJobCreator;
import com.example.jobprocessor.job.idempotency.InvalidIdempotencyKeyException;
import com.example.jobprocessor.job.idempotency.RequestHasher;
import com.example.jobprocessor.job.repository.JobAttemptRepository;
import com.example.jobprocessor.job.repository.JobRepository;
import com.example.jobprocessor.job.repository.JobStateRepository;
import com.example.jobprocessor.job.simulation.InvalidJobPayloadException;
import com.example.jobprocessor.job.simulation.SimulationSpecParser;
import com.example.jobprocessor.security.authorization.AuthenticatedUser;
import com.example.jobprocessor.security.authorization.CurrentUser;
import com.example.jobprocessor.user.domain.Role;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobManagementServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();

    @Mock
    private JobRepository jobRepository;
    @Mock
    private JobAttemptRepository attemptRepository;
    @Mock
    private JobStateRepository jobStates;
    @Mock
    private IdempotentJobCreator jobCreator;
    @Mock
    private CurrentUser currentUser;

    private JobManagementService service;
    private final Job job = Job.newPending(OWNER, Map.of(), 3);

    @BeforeEach
    void setUp() {
        service = new JobManagementService(jobRepository, attemptRepository, jobStates, jobCreator, new RequestHasher(),
                new SimulationSpecParser(TestProperties.create(false)), currentUser, TestProperties.create(false));
        when(currentUser.get()).thenReturn(new AuthenticatedUser(OWNER, "alice", Role.USER));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
    }

    @Nested
    class Cancel {

        @Test
        void pendingJobIsCancelledImmediately() {
            when(jobStates.lockStatusForOwner(JOB_ID, OWNER)).thenReturn(Optional.of(JobStatus.PENDING));
            when(jobStates.cancelPending(JOB_ID)).thenReturn(1);

            assertThat(service.cancelJob(JOB_ID).outcome()).isEqualTo(CancelOutcome.CANCELLED);
            verify(jobStates, never()).requestCancellation(any());
        }

        @Test
        void runningJobIsAskedToStop() {
            when(jobStates.lockStatusForOwner(JOB_ID, OWNER)).thenReturn(Optional.of(JobStatus.RUNNING));
            when(jobStates.requestCancellation(JOB_ID)).thenReturn(1);

            assertThat(service.cancelJob(JOB_ID).outcome()).isEqualTo(CancelOutcome.CANCEL_REQUESTED);
            verify(jobStates, never()).cancelPending(any());
        }

        @Test
        void repeatedRequestWhileCancellingChangesNothing() {
            when(jobStates.lockStatusForOwner(JOB_ID, OWNER)).thenReturn(Optional.of(JobStatus.CANCEL_REQUESTED));

            assertThat(service.cancelJob(JOB_ID).outcome()).isEqualTo(CancelOutcome.ALREADY_CANCEL_REQUESTED);
            verifyNoUpdates();
        }

        @Test
        void repeatedRequestAfterCancellationChangesNothing() {
            when(jobStates.lockStatusForOwner(JOB_ID, OWNER)).thenReturn(Optional.of(JobStatus.CANCELLED));

            assertThat(service.cancelJob(JOB_ID).outcome()).isEqualTo(CancelOutcome.ALREADY_CANCELLED);
            verifyNoUpdates();
        }

        @ParameterizedTest
        @EnumSource(value = JobStatus.class, names = {"COMPLETED", "FAILED"})
        void finishedJobsCannotBeCancelled(JobStatus status) {
            when(jobStates.lockStatusForOwner(JOB_ID, OWNER)).thenReturn(Optional.of(status));

            assertThatThrownBy(() -> service.cancelJob(JOB_ID))
                    .isInstanceOf(JobNotCancellableException.class)
                    .hasMessageContaining(status.name());
            verifyNoUpdates();
        }

        @Test
        void missingOrForeignJobIsNotFound() {
            when(jobStates.lockStatusForOwner(JOB_ID, OWNER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelJob(JOB_ID)).isInstanceOf(JobNotFoundException.class);
            verifyNoUpdates();
        }

        @Test
        void updateThatDoesNotApplyIsAnError() {
            when(jobStates.lockStatusForOwner(JOB_ID, OWNER)).thenReturn(Optional.of(JobStatus.PENDING));
            when(jobStates.cancelPending(JOB_ID)).thenReturn(0);

            assertThatThrownBy(() -> service.cancelJob(JOB_ID)).isInstanceOf(IllegalStateException.class);
        }

        private void verifyNoUpdates() {
            verify(jobStates, never()).cancelPending(any());
            verify(jobStates, never()).requestCancellation(any());
        }
    }

    @Nested
    class Create {

        private final CreateJobRequest request = new CreateJobRequest(Map.of("someParameter", "someValue"));

        @Test
        void returnsCreatedJob() {
            when(jobCreator.create(eq(OWNER), eq("key-1"), anyString(), any(), eq(3), eq(Duration.ofHours(24))))
                    .thenReturn(new IdempotentJobCreator.Created(job));

            var result = service.createJob("key-1", request);

            assertThat(result.job()).isSameAs(job);
            assertThat(result.replayed()).isFalse();
        }

        @Test
        void replayReturnsTheOriginalOwnedJob() {
            when(jobCreator.create(any(), any(), any(), any(), anyInt(), any()))
                    .thenReturn(new IdempotentJobCreator.Replayed(JOB_ID));
            when(jobRepository.findByIdAndOwnerId(JOB_ID, OWNER)).thenReturn(Optional.of(job));

            var result = service.createJob("key-1", request);

            assertThat(result.job()).isSameAs(job);
            assertThat(result.replayed()).isTrue();
        }

        @Test
        void conflictPropagates() {
            when(jobCreator.create(any(), any(), any(), any(), anyInt(), any()))
                    .thenThrow(new IdempotencyKeyConflictException());

            assertThatThrownBy(() -> service.createJob("key-1", request)).isInstanceOf(IdempotencyKeyConflictException.class);
        }

        @Test
        void invalidKeyIsRejectedBeforeAnyDatabaseWork() {
            assertThatThrownBy(() -> service.createJob(" ", request)).isInstanceOf(InvalidIdempotencyKeyException.class);
            verifyNoInteractions(jobCreator, jobRepository);
        }

        @Test
        void invalidSimulationIsRejectedBeforeAnyDatabaseWork() {
            var injected = new CreateJobRequest(Map.of("simulation", Map.of("failureMode", "PERMANENT")));

            assertThatThrownBy(() -> service.createJob("key-1", injected)).isInstanceOf(InvalidJobPayloadException.class);
            verifyNoInteractions(jobCreator, jobRepository);
        }
    }

    @Nested
    class Read {

        @Test
        void attemptsOfForeignJobAreNotFound() {
            when(jobRepository.existsByIdAndOwnerId(JOB_ID, OWNER)).thenReturn(false);

            assertThatThrownBy(() -> service.getAttempts(JOB_ID)).isInstanceOf(JobNotFoundException.class);
            verifyNoInteractions(attemptRepository);
        }

        @Test
        void getJobIsScopedToTheCaller() {
            when(jobRepository.findByIdAndOwnerId(JOB_ID, OWNER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getJob(JOB_ID)).isInstanceOf(JobNotFoundException.class);
            verify(jobRepository, never()).findById(any());
        }
    }
}
