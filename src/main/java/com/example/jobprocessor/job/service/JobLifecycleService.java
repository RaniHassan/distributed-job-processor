package com.example.jobprocessor.job.service;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.domain.AttemptStatus;
import com.example.jobprocessor.job.domain.JobStatus;
import com.example.jobprocessor.job.lifecycle.FailureType;
import com.example.jobprocessor.job.lifecycle.JobStateMachine;
import com.example.jobprocessor.job.lifecycle.RetryPolicy;
import com.example.jobprocessor.job.repository.JobStateRepository;
import com.example.jobprocessor.job.repository.JobStateRepository.ClaimRow;
import com.example.jobprocessor.job.repository.JobStateRepository.ExpiredLease;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job Management's internal lifecycle operations for the Dispatcher, Workers, Heartbeat and
 * Recovery Manager (README §8.3).
 * Not reachable over HTTP and not user-authorized; instead every operation on a claimed job is
 * fenced: it applies only while the job is still owned by the same worker with the same fencing
 * token, so a stale worker can never overwrite newer state. Job and attempt are always updated in
 * one transaction.
 */
@Service
public class JobLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleService.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };
    static final int MAX_ERROR_LENGTH = 2000;
    // Visible to the job's owner: no worker or infrastructure details.
    static final String ABANDONED_ATTEMPT = "Lease expired: the worker stopped renewing it";
    static final String LEASE_EXPIRED_RETRYING = "[ABANDONED] Processing was interrupted; retry scheduled";
    static final String LEASE_EXPIRED_EXHAUSTED = "[ABANDONED] Processing was interrupted; no retries left";

    private final JobStateRepository jobStates;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;
    private final Duration leaseDuration;

    public JobLifecycleService(JobStateRepository jobStates, RetryPolicy retryPolicy, ObjectMapper objectMapper,
                               JobProcessorProperties properties) {
        this.jobStates = jobStates;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
        this.leaseDuration = properties.lease().duration();
    }

    /**
     * Atomically claims the oldest eligible job and opens its attempt (README §10). The transaction
     * commits before the caller starts executing; it is never held during execution.
     */
    @Transactional
    public Optional<ClaimedJob> claimNext(String workerId) {
        JobStateMachine.requireTransition(JobStatus.PENDING, JobStatus.RUNNING);
        Optional<ClaimRow> claimed = jobStates.claimNext(workerId, leaseDuration);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimRow row = claimed.get();
        jobStates.insertRunningAttempt(row.jobId(), row.fencingToken(), workerId);
        log.info("Job claimed: jobId={} workerId={} fencingToken={}", row.jobId(), workerId, row.fencingToken());
        return Optional.of(new ClaimedJob(row.jobId(), workerId, row.fencingToken(), parsePayload(row.payloadJson())));
    }

    /** @return {@code false} if this worker no longer owns the job; nothing is written then */
    @Transactional
    public boolean complete(ClaimedJob job) {
        return finish(job, JobStatus.COMPLETED, AttemptStatus.SUCCEEDED, null,
                () -> jobStates.complete(job.jobId(), job.workerId(), job.fencingToken()));
    }

    /**
     * Records a failed attempt. Until the retry policy exists, every failure is final; the
     * classification is kept in the error message.
     *
     * @return {@code false} if this worker no longer owns the job; nothing is written then
     */
    @Transactional
    public boolean fail(ClaimedJob job, FailureType type, String message) {
        String error = truncate("[" + type + "] " + message);
        return finish(job, JobStatus.FAILED, AttemptStatus.FAILED, error,
                () -> jobStates.fail(job.jobId(), job.workerId(), job.fencingToken(), error));
    }

    /**
     * Gives a claimed job back without running it (the local pool rejected it). The attempt is
     * marked ABANDONED and no retry is consumed; the job is immediately claimable again.
     */
    @Transactional
    public boolean releaseClaim(ClaimedJob job, String reason) {
        return finish(job, JobStatus.PENDING, AttemptStatus.ABANDONED, truncate(reason),
                () -> jobStates.release(job.jobId(), job.workerId(), job.fencingToken()));
    }

    /**
     * Heartbeat for one claimed job (README §11.2). Only the current owner of a RUNNING job extends
     * its lease; any other answer means the worker must stop and must not record a result.
     */
    @Transactional
    public LeaseStatus renewLease(ClaimedJob job) {
        if (jobStates.renewLease(job.jobId(), job.workerId(), job.fencingToken(), leaseDuration) == 1) {
            return LeaseStatus.RENEWED;
        }
        return jobStates.statusIfOwned(job.jobId(), job.workerId(), job.fencingToken())
                .filter(status -> status == JobStatus.CANCEL_REQUESTED)
                .map(status -> LeaseStatus.CANCEL_REQUESTED)
                .orElse(LeaseStatus.LOST);
    }

    /** Which of these claims (all owned by {@code workerId}) have a pending cancellation request. */
    @Transactional(readOnly = true)
    public Set<UUID> findCancellationRequests(String workerId, Collection<ClaimedJob> jobs) {
        if (jobs.isEmpty()) {
            return Set.of();
        }
        return jobStates.cancellationRequested(workerId,
                jobs.stream().map(ClaimedJob::jobId).toList(),
                jobs.stream().map(ClaimedJob::fencingToken).toList());
    }

    /**
     * Takes over the job whose lease expired longest ago (README §13), in one transaction: the
     * owner's attempt becomes ABANDONED and the job is requeued (retries left), failed (retries
     * exhausted) or, if cancellation was requested, finalized as CANCELLED. Ownership fields are
     * cleared, so the previous worker cannot update the job even if it is still alive.
     *
     * @return empty when no lease has expired (or all expired ones are being recovered elsewhere)
     */
    @Transactional
    public Optional<RecoveredJob> recoverNextExpiredLease() {
        Optional<ExpiredLease> next = jobStates.lockNextExpiredLease();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        ExpiredLease lease = next.get();
        if (jobStates.closeAttempt(lease.jobId(), lease.fencingToken(), AttemptStatus.ABANDONED, ABANDONED_ATTEMPT) != 1) {
            log.warn("No open attempt for recovered job: jobId={} fencingToken={}", lease.jobId(), lease.fencingToken());
        }

        RecoveredJob recovered;
        if (lease.status() == JobStatus.CANCEL_REQUESTED) {
            JobStateMachine.requireTransition(JobStatus.CANCEL_REQUESTED, JobStatus.CANCELLED);
            requireOneRow(jobStates.confirmCancelled(lease.jobId(), lease.workerId(), lease.fencingToken()), lease);
            recovered = recovered(lease, RecoveredJob.Outcome.CANCELLED, lease.retryCount(), null);
        } else if (retryPolicy.hasRetriesLeft(lease.retryCount(), lease.maxRetries())) {
            JobStateMachine.requireTransition(JobStatus.RUNNING, JobStatus.PENDING);
            int retryNumber = lease.retryCount() + 1;
            Duration delay = retryPolicy.delayBeforeRetry(retryNumber);
            requireOneRow(jobStates.requeue(lease.jobId(), lease.workerId(), lease.fencingToken(), delay,
                    LEASE_EXPIRED_RETRYING), lease);
            recovered = recovered(lease, RecoveredJob.Outcome.REQUEUED, retryNumber, delay);
        } else {
            JobStateMachine.requireTransition(JobStatus.RUNNING, JobStatus.FAILED);
            requireOneRow(jobStates.fail(lease.jobId(), lease.workerId(), lease.fencingToken(), LEASE_EXPIRED_EXHAUSTED), lease);
            recovered = recovered(lease, RecoveredJob.Outcome.FAILED, lease.retryCount(), null);
        }
        log.warn("Lease expired, job recovered: jobId={} previousWorker={} fencingToken={} outcome={} retryCount={} retryDelay={}",
                lease.jobId(), lease.workerId(), lease.fencingToken(), recovered.outcome(), recovered.retryCount(),
                recovered.retryDelay());
        return Optional.of(recovered);
    }

    private static RecoveredJob recovered(ExpiredLease lease, RecoveredJob.Outcome outcome, int retryCount, Duration delay) {
        return new RecoveredJob(lease.jobId(), lease.workerId(), lease.fencingToken(), outcome, retryCount, delay);
    }

    /** The row is locked and the update repeats the locked values, so anything but one row is a bug. */
    private static void requireOneRow(int rows, ExpiredLease lease) {
        if (rows != 1) {
            throw new IllegalStateException("Recovery update did not apply to locked job " + lease.jobId());
        }
    }

    private boolean finish(ClaimedJob job, JobStatus target, AttemptStatus attemptStatus, String error,
                           IntSupplier jobUpdate) {
        JobStateMachine.requireTransition(JobStatus.RUNNING, target);
        if (jobUpdate.getAsInt() != 1) {
            log.warn("Ownership lost, result not recorded: jobId={} workerId={} fencingToken={} intended={}",
                    job.jobId(), job.workerId(), job.fencingToken(), target);
            return false;
        }
        if (jobStates.closeAttempt(job.jobId(), job.fencingToken(), attemptStatus, error) != 1) {
            // The owner's attempt must be open; roll back rather than leave job and attempt inconsistent.
            throw new IllegalStateException("No open attempt " + job.attemptNumber() + " for job " + job.jobId());
        }
        log.info("Job {}: jobId={} workerId={} fencingToken={}", target, job.jobId(), job.workerId(), job.fencingToken());
        return true;
    }

    private Map<String, Object> parsePayload(String json) {
        try {
            return objectMapper.readValue(json, PAYLOAD_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored job payload is not valid JSON", e);
        }
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }
}
