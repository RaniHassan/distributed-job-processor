package com.example.jobprocessor.job.idempotency;

import com.example.jobprocessor.job.domain.Job;
import com.example.jobprocessor.job.idempotency.IdempotencyKeyStore.StoredKey;
import com.example.jobprocessor.job.repository.JobRepository;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Creates a job and binds its idempotency key in one transaction (README §15.4).
 * <ul>
 *   <li>Live key, same request hash: replay; nothing is written.</li>
 *   <li>Live key, different hash: 409 conflict.</li>
 *   <li>No key or an expired one: the job is inserted and the key bound to it.</li>
 * </ul>
 * When a concurrent request binds the same key first, this transaction is rolled back, so the
 * job it inserted never becomes visible, and the winner's job is replayed (or 409 is returned).
 * Callers must not wrap this in an outer transaction, which the rollback would poison.
 */
@Component
public class IdempotentJobCreator {

    private final JobRepository jobRepository;
    private final IdempotencyKeyStore keyStore;

    public IdempotentJobCreator(JobRepository jobRepository, IdempotencyKeyStore keyStore) {
        this.jobRepository = jobRepository;
        this.keyStore = keyStore;
    }

    @Transactional
    public Outcome create(UUID ownerId, String key, String requestHash, Map<String, Object> payload,
                          int maxRetries, Duration retention) {
        var existing = keyStore.findLive(ownerId, key);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        Job job = jobRepository.saveAndFlush(Job.newPending(ownerId, payload, maxRetries));
        if (keyStore.bindKey(ownerId, key, requestHash, job.getId(), retention)) {
            return new Created(job);
        }

        // Lost the race: another request committed this key after our first lookup.
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        StoredKey winner = keyStore.findLive(ownerId, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key conflict without a live key"));
        return replayOrConflict(winner, requestHash);
    }

    private static Outcome replayOrConflict(StoredKey stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyConflictException();
        }
        return new Replayed(stored.jobId());
    }

    public sealed interface Outcome permits Created, Replayed {
    }

    public record Created(Job job) implements Outcome {
    }

    public record Replayed(UUID jobId) implements Outcome {
    }
}
