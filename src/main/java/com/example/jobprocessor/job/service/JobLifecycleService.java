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
import com.example.jobprocessor.job.repository.JobStateRepository.OwnedJob;
import com.example.jobprocessor.monitoring.metrics.JobMetrics;
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
import org.slf4j.MDC;
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
    private final JobMetrics metrics;
    private final Duration leaseDuration;

    public JobLifecycleService(JobStateRepository jobStates, RetryPolicy retryPolicy, ObjectMapper objectMapper,
                               JobMetrics metrics, JobProcessorProperties properties) {
        this.jobStates = jobStates;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
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
        metrics.jobClaimed(row.queueWait());
        log.info("Job claimed: jobId={} workerId={} fencingToken={} queueWait={}ms",
                row.jobId(), workerId, row.fencingToken(), row.queueWait().toMillis());
        return Optional.of(new ClaimedJob(row.jobId(), workerId, row.fencingToken(), parsePayload(row.payloadJson())));
    }

    /*
     * Recording a worker's result. Each operation locks the job row for this exact ownership
     * generation and decides from the status it finds. A cancellation request also locks the row,
     * so the two are strictly ordered (README §9.2): if cancellation committed first, the result
     * becomes CANCELLED and COMPLETED is never written; if completion committed first, the
     * cancellation request sees a finished job and is rejected.
     */

    /** Successful execution: COMPLETED, or CANCELLED if cancellation was requested meanwhile. */
    @Transactional
    public RecordedOutcome complete(ClaimedJob job) {
        return counted(recordCompletion(job));
    }

    private RecordedOutcome recordCompletion(ClaimedJob job) {
        Optional<OwnedJob> owned = jobStates.lockOwned(job.jobId(), job.workerId(), job.fencingToken());
        if (owned.isEmpty()) {
            return ownershipLost(job, "completion");
        }
        return switch (owned.get().status()) {
            case RUNNING -> {
                apply(job, JobStatus.RUNNING, JobStatus.COMPLETED, AttemptStatus.SUCCEEDED, null,
                        () -> jobStates.complete(job.jobId(), job.workerId(), job.fencingToken()));
                yield RecordedOutcome.COMPLETED;
            }
            case CANCEL_REQUESTED -> cancel(job, "Finished after cancellation was requested; result discarded");
            default -> ownershipLost(job, "completion");
        };
    }

    /**
     * Failed execution (README §12). Retryable failures (TRANSIENT, and UNEXPECTED within the same
     * bounded budget) go back to PENDING with backoff while retries remain; PERMANENT failures and
     * exhausted budgets end FAILED. A pending cancellation request takes precedence: the job ends
     * CANCELLED, not FAILED.
     */
    @Transactional
    public RecordedOutcome fail(ClaimedJob job, FailureType type, String message) {
        return counted(recordFailure(job, type, message));
    }

    private RecordedOutcome recordFailure(ClaimedJob job, FailureType type, String message) {
        String error = truncate("[" + type + "] " + message);
        Optional<OwnedJob> owned = jobStates.lockOwned(job.jobId(), job.workerId(), job.fencingToken());
        if (owned.isEmpty()) {
            return ownershipLost(job, "failure");
        }
        OwnedJob state = owned.get();
        return switch (state.status()) {
            case RUNNING -> {
                if (retryPolicy.shouldRetry(type, state.retryCount(), state.maxRetries())) {
                    Duration delay = retryPolicy.delayBeforeRetry(state.retryCount() + 1);
                    apply(job, JobStatus.RUNNING, JobStatus.PENDING, AttemptStatus.FAILED, error,
                            () -> jobStates.requeue(job.jobId(), job.workerId(), job.fencingToken(), delay, error));
                    log.info("Retry scheduled: jobId={} retry={}/{} delay={}", job.jobId(), state.retryCount() + 1,
                            state.maxRetries(), delay);
                    yield RecordedOutcome.RETRY_SCHEDULED;
                }
                apply(job, JobStatus.RUNNING, JobStatus.FAILED, AttemptStatus.FAILED, error,
                        () -> jobStates.fail(job.jobId(), job.workerId(), job.fencingToken(), error));
                yield RecordedOutcome.FAILED;
            }
            case CANCEL_REQUESTED -> cancel(job, error + " (cancellation was requested)");
            default -> ownershipLost(job, "failure");
        };
    }

    /** The worker stopped because cancellation was requested: CANCEL_REQUESTED -> CANCELLED. */
    @Transactional
    public RecordedOutcome confirmCancelled(ClaimedJob job) {
        Optional<OwnedJob> owned = jobStates.lockOwned(job.jobId(), job.workerId(), job.fencingToken());
        if (owned.isPresent() && owned.get().status() == JobStatus.CANCEL_REQUESTED) {
            return counted(cancel(job, null));
        }
        return counted(ownershipLost(job, "cancellation"));
    }

    /** Counts the recorded result once this transaction commits. */
    private RecordedOutcome counted(RecordedOutcome outcome) {
        metrics.resultRecorded(outcome);
        return outcome;
    }

    /**
     * Gives a claimed job back without running it (the local pool rejected it). The attempt is
     * marked ABANDONED and no retry is consumed; the job is immediately claimable again.
     */
    @Transactional
    public boolean releaseClaim(ClaimedJob job, String reason) {
        Optional<OwnedJob> owned = jobStates.lockOwned(job.jobId(), job.workerId(), job.fencingToken());
        if (owned.isEmpty() || owned.get().status() != JobStatus.RUNNING) {
            ownershipLost(job, "release");
            return false;
        }
        apply(job, JobStatus.RUNNING, JobStatus.PENDING, AttemptStatus.ABANDONED, truncate(reason),
                () -> jobStates.release(job.jobId(), job.workerId(), job.fencingToken()));
        return true;
    }

    private RecordedOutcome cancel(ClaimedJob job, String attemptNote) {
        apply(job, JobStatus.CANCEL_REQUESTED, JobStatus.CANCELLED, AttemptStatus.CANCELLED, attemptNote,
                () -> jobStates.confirmCancelled(job.jobId(), job.workerId(), job.fencingToken()));
        return RecordedOutcome.CANCELLED;
    }

    private static RecordedOutcome ownershipLost(ClaimedJob job, String what) {
        log.warn("Ownership lost, {} not recorded: jobId={} workerId={} fencingToken={}",
                what, job.jobId(), job.workerId(), job.fencingToken());
        return RecordedOutcome.OWNERSHIP_LOST;
    }

    /**
     * Applies a validated transition to a job locked for this generation, and closes its attempt,
     * in the caller's transaction. With the row locked, anything other than one updated row each
     * is a bug, so the transaction is rolled back rather than left half-applied.
     */
    private void apply(ClaimedJob job, JobStatus from, JobStatus to, AttemptStatus attemptStatus, String note,
                       IntSupplier jobUpdate) {
        JobStateMachine.requireTransition(from, to);
        if (jobUpdate.getAsInt() != 1) {
            throw new IllegalStateException("Transition " + from + " -> " + to + " did not apply to locked job " + job.jobId());
        }
        if (jobStates.closeAttempt(job.jobId(), job.fencingToken(), attemptStatus, note) != 1) {
            throw new IllegalStateException("No open attempt " + job.attemptNumber() + " for job " + job.jobId());
        }
        log.info("Job {}: jobId={} workerId={} fencingToken={}", to, job.jobId(), job.workerId(), job.fencingToken());
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
        try (MDC.MDCCloseable ignored = MDC.putCloseable("jobId", lease.jobId().toString())) {
            return Optional.of(recover(lease));
        }
    }

    private RecoveredJob recover(ExpiredLease lease) {
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
        metrics.leaseExpired(recovered.outcome());
        return recovered;
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
