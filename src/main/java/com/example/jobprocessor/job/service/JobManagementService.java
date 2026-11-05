package com.example.jobprocessor.job.service;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.domain.Job;
import com.example.jobprocessor.job.domain.JobAttempt;
import com.example.jobprocessor.job.domain.JobStatus;
import com.example.jobprocessor.job.dto.CreateJobRequest;
import com.example.jobprocessor.job.idempotency.IdempotencyKeys;
import com.example.jobprocessor.job.idempotency.IdempotentJobCreator;
import com.example.jobprocessor.job.idempotency.IdempotentJobCreator.Created;
import com.example.jobprocessor.job.idempotency.IdempotentJobCreator.Replayed;
import com.example.jobprocessor.job.idempotency.RequestHasher;
import com.example.jobprocessor.job.lifecycle.JobStateMachine;
import com.example.jobprocessor.job.repository.JobAttemptRepository;
import com.example.jobprocessor.job.repository.JobRepository;
import com.example.jobprocessor.job.repository.JobStateRepository;
import com.example.jobprocessor.job.simulation.SimulationSpecParser;
import com.example.jobprocessor.security.authorization.AuthenticatedUser;
import com.example.jobprocessor.security.authorization.CurrentUser;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner of the job lifecycle (README §8.3). Client operations are authorized here, not only in
 * the web layer: each method requires its permission, and every lookup is scoped to the
 * authenticated caller, so another user's job is indistinguishable from a missing one.
 */
@Service
public class JobManagementService {

    private static final Logger log = LoggerFactory.getLogger(JobManagementService.class);

    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final JobStateRepository jobStates;
    private final IdempotentJobCreator jobCreator;
    private final RequestHasher requestHasher;
    private final SimulationSpecParser simulationParser;
    private final CurrentUser currentUser;
    private final JobProcessorProperties properties;

    public JobManagementService(JobRepository jobRepository, JobAttemptRepository attemptRepository,
                                JobStateRepository jobStates, IdempotentJobCreator jobCreator,
                                RequestHasher requestHasher, SimulationSpecParser simulationParser,
                                CurrentUser currentUser, JobProcessorProperties properties) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.jobStates = jobStates;
        this.jobCreator = jobCreator;
        this.requestHasher = requestHasher;
        this.simulationParser = simulationParser;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    /** Not transactional itself: {@link IdempotentJobCreator} owns the transaction and may roll it back. */
    @PreAuthorize("hasAuthority('JOB_CREATE')")
    public CreateJobResult createJob(String idempotencyKey, CreateJobRequest request) {
        IdempotencyKeys.validate(idempotencyKey);
        simulationParser.parse(request.payload());
        AuthenticatedUser user = currentUser.get();

        var outcome = jobCreator.create(user.id(), idempotencyKey, requestHasher.hash(request), request.payload(),
                properties.retry().maxRetries(), properties.idempotency().retention());

        return switch (outcome) {
            case Created created -> {
                log.info("Job created: jobId={} ownerId={}", created.job().getId(), user.id());
                yield new CreateJobResult(created.job(), false);
            }
            case Replayed replayed -> {
                log.info("Idempotent replay: jobId={} ownerId={}", replayed.jobId(), user.id());
                yield new CreateJobResult(findOwned(replayed.jobId(), user.id()), true);
            }
        };
    }

    @PreAuthorize("hasAuthority('JOB_READ')")
    public Job getJob(UUID jobId) {
        return findOwned(jobId, currentUser.get().id());
    }

    @PreAuthorize("hasAuthority('JOB_READ')")
    public Page<Job> listJobs(Pageable pageable) {
        return jobRepository.findByOwnerId(currentUser.get().id(), pageable);
    }

    @PreAuthorize("hasAuthority('JOB_READ')")
    public List<JobAttempt> getAttempts(UUID jobId) {
        if (!jobRepository.existsByIdAndOwnerId(jobId, currentUser.get().id())) {
            throw new JobNotFoundException();
        }
        return attemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
    }

    /**
     * Requests cancellation (README §9.1). The row is locked first, so the decision cannot race a
     * concurrent claim: a PENDING job is cancelled at once, a RUNNING one moves to CANCEL_REQUESTED
     * for its worker to finish, repeated requests are accepted unchanged, and finished jobs are 409.
     */
    @PreAuthorize("hasAuthority('JOB_CANCEL')")
    @Transactional
    public CancellationResult cancelJob(UUID jobId) {
        UUID ownerId = currentUser.get().id();
        JobStatus status = jobStates.lockStatusForOwner(jobId, ownerId).orElseThrow(JobNotFoundException::new);

        CancelOutcome outcome = switch (status) {
            case PENDING -> {
                transition(jobId, JobStatus.PENDING, JobStatus.CANCELLED, jobStates::cancelPending);
                log.info("Job cancelled: jobId={} ownerId={}", jobId, ownerId);
                yield CancelOutcome.CANCELLED;
            }
            case RUNNING -> {
                transition(jobId, JobStatus.RUNNING, JobStatus.CANCEL_REQUESTED, jobStates::requestCancellation);
                log.info("Cancellation requested: jobId={} ownerId={}", jobId, ownerId);
                yield CancelOutcome.CANCEL_REQUESTED;
            }
            case CANCEL_REQUESTED -> CancelOutcome.ALREADY_CANCEL_REQUESTED;
            case CANCELLED -> CancelOutcome.ALREADY_CANCELLED;
            case COMPLETED, FAILED -> throw new JobNotCancellableException(status);
        };
        return new CancellationResult(jobRepository.findById(jobId).orElseThrow(), outcome);
    }

    private static void transition(UUID jobId, JobStatus from, JobStatus to, ToIntFunction<UUID> update) {
        JobStateMachine.requireTransition(from, to);
        if (update.applyAsInt(jobId) != 1) {
            // Impossible while the row lock is held; fail loudly rather than report a change that did not happen.
            throw new IllegalStateException("Transition " + from + " -> " + to + " did not apply to job " + jobId);
        }
    }

    private Job findOwned(UUID jobId, UUID ownerId) {
        return jobRepository.findByIdAndOwnerId(jobId, ownerId).orElseThrow(JobNotFoundException::new);
    }

    public record CreateJobResult(Job job, boolean replayed) {
    }

    public record CancellationResult(Job job, CancelOutcome outcome) {
    }
}
