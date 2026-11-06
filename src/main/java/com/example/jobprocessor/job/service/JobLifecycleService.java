package com.example.jobprocessor.job.service;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.job.domain.AttemptStatus;
import com.example.jobprocessor.job.domain.JobStatus;
import com.example.jobprocessor.job.lifecycle.FailureType;
import com.example.jobprocessor.job.lifecycle.JobStateMachine;
import com.example.jobprocessor.job.repository.JobStateRepository;
import com.example.jobprocessor.job.repository.JobStateRepository.ClaimRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job Management's internal lifecycle operations for the Dispatcher and Workers (README §8.3).
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

    private final JobStateRepository jobStates;
    private final ObjectMapper objectMapper;
    private final Duration leaseDuration;

    public JobLifecycleService(JobStateRepository jobStates, ObjectMapper objectMapper, JobProcessorProperties properties) {
        this.jobStates = jobStates;
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
