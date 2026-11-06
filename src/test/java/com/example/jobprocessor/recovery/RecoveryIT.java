package com.example.jobprocessor.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.job.lifecycle.FailureType;
import com.example.jobprocessor.job.service.ClaimedJob;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.LeaseStatus;
import com.example.jobprocessor.job.service.RecoveredJob;
import com.example.jobprocessor.recovery.service.RecoveryManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recovery of expired leases against real PostgreSQL (README §13). The scheduled Recovery Manager
 * is disabled in this context, so every recovery here is triggered explicitly.
 * Test profile: 3 retries, backoff 200/400/800 ms, no jitter.
 */
@ApiIntegrationTest
class RecoveryIT {

    @Autowired
    private JobLifecycleService lifecycle;
    @Autowired
    private RecoveryManager recoveryManager;
    @Autowired
    private TestRows rows;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private UUID owner;

    @BeforeEach
    void cleanDatabase() {
        rows.deleteAllJobs();
        owner = rows.insertUser();
    }

    /** A job claimed by {@code workerId} whose lease has already expired, as after a crash. */
    private ClaimedJob abandonedClaim(String workerId) {
        rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext(workerId).orElseThrow();
        expireLease(claimed.jobId());
        return claimed;
    }

    private void expireLease(UUID jobId) {
        jdbc.update("UPDATE jobs SET lease_until = now() - interval '1 second' WHERE id = ?", jobId);
    }

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("SELECT * FROM job_attempts WHERE job_id = ? ORDER BY attempt_number", id);
    }

    @Test
    void expiredRunningJobWithRetriesLeftIsRequeuedWithBackoff() {
        ClaimedJob crashed = abandonedClaim("crashed-worker");

        RecoveredJob recovered = lifecycle.recoverNextExpiredLease().orElseThrow();

        assertThat(recovered.outcome()).isEqualTo(RecoveredJob.Outcome.REQUEUED);
        assertThat(recovered.previousWorkerId()).isEqualTo("crashed-worker");
        assertThat(recovered.retryCount()).isEqualTo(1);
        assertThat(recovered.retryDelay()).isEqualTo(Duration.ofMillis(200));

        Map<String, Object> row = job(crashed.jobId());
        assertThat(row).containsEntry("status", "PENDING").containsEntry("retry_count", 1).containsEntry("fencing_token", 1L)
                .containsEntry("error_message", "[ABANDONED] Processing was interrupted; retry scheduled");
        assertThat(row.get("worker_id")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("heartbeat_at")).isNull();
        assertThat(row.get("finished_at")).isNull();
        Boolean delayed = jdbc.queryForObject(
                "SELECT next_retry_at > now() + interval '100 milliseconds' FROM jobs WHERE id = ?", Boolean.class, crashed.jobId());
        assertThat(delayed).as("next_retry_at honours the backoff").isTrue();

        Map<String, Object> attempt = attempts(crashed.jobId()).get(0);
        assertThat(attempt).containsEntry("status", "ABANDONED").containsEntry("worker_id", "crashed-worker")
                .containsEntry("error_message", "Lease expired: the worker stopped renewing it");
        assertThat(attempt.get("finished_at")).isNotNull();
    }

    @Test
    void requeuedJobIsClaimedAgainAsANewGenerationAfterTheBackoff() throws Exception {
        ClaimedJob crashed = abandonedClaim("crashed-worker");
        lifecycle.recoverNextExpiredLease().orElseThrow();

        assertThat(lifecycle.claimNext("healthy-worker")).as("not before the backoff").isEmpty();
        Thread.sleep(300);
        ClaimedJob retry = lifecycle.claimNext("healthy-worker").orElseThrow();

        assertThat(retry.jobId()).isEqualTo(crashed.jobId());
        assertThat(retry.fencingToken()).isEqualTo(2);
        assertThat(lifecycle.complete(retry)).isTrue();
        assertThat(attempts(crashed.jobId())).extracting(a -> a.get("status")).containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(job(crashed.jobId())).containsEntry("status", "COMPLETED").containsEntry("retry_count", 1);
    }

    @Test
    void expiredJobWithoutRetriesLeftFails() {
        rows.job(owner).set("retry_count", 3).set("max_retries", 3).insert();
        ClaimedJob claimed = lifecycle.claimNext("crashed-worker").orElseThrow();
        expireLease(claimed.jobId());

        RecoveredJob recovered = lifecycle.recoverNextExpiredLease().orElseThrow();

        assertThat(recovered.outcome()).isEqualTo(RecoveredJob.Outcome.FAILED);
        Map<String, Object> row = job(claimed.jobId());
        assertThat(row).containsEntry("status", "FAILED").containsEntry("retry_count", 3)
                .containsEntry("error_message", "[ABANDONED] Processing was interrupted; no retries left");
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("worker_id")).isNull();
        assertThat(attempts(claimed.jobId()).get(0)).containsEntry("status", "ABANDONED");
    }

    @Test
    void expiredCancellationIsFinalizedAsCancelledWithoutUsingARetry() {
        ClaimedJob claimed = abandonedClaim("stuck-worker");
        jdbc.update("UPDATE jobs SET status = 'CANCEL_REQUESTED', cancel_requested_at = now() WHERE id = ?", claimed.jobId());

        RecoveredJob recovered = lifecycle.recoverNextExpiredLease().orElseThrow();

        assertThat(recovered.outcome()).isEqualTo(RecoveredJob.Outcome.CANCELLED);
        Map<String, Object> row = job(claimed.jobId());
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("retry_count", 0);
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("worker_id")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("heartbeat_at")).isNull();
        assertThat(attempts(claimed.jobId()).get(0)).containsEntry("status", "ABANDONED");
    }

    @Test
    void liveLeasesAndOtherStatesAreLeftAlone() {
        rows.job(owner).insert();
        ClaimedJob live = lifecycle.claimNext("healthy-worker").orElseThrow();
        UUID pending = rows.job(owner).insert();
        UUID completed = rows.job(owner).completed().insert();

        assertThat(recoveryManager.recoverExpiredLeases()).isEmpty();
        assertThat(job(live.jobId())).containsEntry("status", "RUNNING").containsEntry("worker_id", "healthy-worker");
        assertThat(job(pending)).containsEntry("status", "PENDING");
        assertThat(job(completed)).containsEntry("status", "COMPLETED");
    }

    @Test
    void recoversOldestExpiredLeaseFirst() {
        ClaimedJob newer = abandonedClaim("w1");
        ClaimedJob older = abandonedClaim("w2");
        jdbc.update("UPDATE jobs SET lease_until = now() - interval '1 minute' WHERE id = ?", older.jobId());

        assertThat(lifecycle.recoverNextExpiredLease().orElseThrow().jobId()).isEqualTo(older.jobId());
        assertThat(lifecycle.recoverNextExpiredLease().orElseThrow().jobId()).isEqualTo(newer.jobId());
        assertThat(lifecycle.recoverNextExpiredLease()).isEmpty();
    }

    /** README §13: an expired lease does not prove the old worker stopped; fencing protects the job. */
    @Test
    void staleWorkerCannotWriteAfterRecovery() throws Exception {
        ClaimedJob stale = abandonedClaim("paused-worker");
        lifecycle.recoverNextExpiredLease().orElseThrow();

        // The paused worker wakes up before anyone reclaims the job...
        assertThat(lifecycle.renewLease(stale)).isEqualTo(LeaseStatus.LOST);
        assertThat(lifecycle.complete(stale)).isFalse();
        assertThat(lifecycle.fail(stale, FailureType.TRANSIENT, "late")).isFalse();
        assertThat(job(stale.jobId())).containsEntry("status", "PENDING");

        // ...and after another worker reclaimed it.
        Thread.sleep(300);
        ClaimedJob current = lifecycle.claimNext("new-owner").orElseThrow();
        assertThat(lifecycle.complete(stale)).isFalse();
        assertThat(lifecycle.renewLease(stale)).isEqualTo(LeaseStatus.LOST);
        assertThat(job(stale.jobId())).containsEntry("status", "RUNNING").containsEntry("worker_id", "new-owner")
                .containsEntry("fencing_token", 2L);
        assertThat(lifecycle.complete(current)).isTrue();
    }

    @Test
    void concurrentRecoveryManagersNeverRecoverTheSameJob() throws Exception {
        int jobs = 120;
        for (int i = 0; i < jobs; i++) {
            rows.job(owner).insert();
        }
        for (int i = 0; i < jobs; i++) {
            lifecycle.claimNext("crashed-" + (i % 4)).orElseThrow();
        }
        jdbc.update("UPDATE jobs SET lease_until = now() - interval '1 second'");

        ConcurrentLinkedQueue<RecoveredJob> recovered = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService managers = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int m = 0; m < 6; m++) {
                runs.add(managers.submit(() -> {
                    start.await();
                    Optional<RecoveredJob> next;
                    while ((next = lifecycle.recoverNextExpiredLease()).isPresent()) {
                        recovered.add(next.get());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            managers.shutdownNow();
        }

        Set<UUID> ids = new HashSet<>();
        recovered.forEach(r -> assertThat(ids.add(r.jobId())).as("recovered twice: " + r.jobId()).isTrue());
        assertThat(ids).hasSize(jobs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'PENDING' AND retry_count = 1", Long.class))
                .isEqualTo(jobs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_attempts WHERE status = 'ABANDONED'", Long.class))
                .as("exactly one abandoned attempt per job").isEqualTo(jobs);
    }

    @Test
    void jobLockedByAnotherTransactionIsSkippedAndARenewalThereWins() throws Exception {
        ClaimedJob claimed = abandonedClaim("slow-heartbeat");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            // A heartbeat holding the row lock while it renews the lease.
            Future<?> heartbeat = other.submit(() -> transactions.executeWithoutResult(status -> {
                lifecycle.renewLease(claimed);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(lifecycle.recoverNextExpiredLease()).as("locked row is skipped, not waited for").isEmpty();

            release.countDown();
            heartbeat.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            other.shutdownNow();
        }

        assertThat(lifecycle.recoverNextExpiredLease()).as("the renewed lease is no longer expired").isEmpty();
        assertThat(job(claimed.jobId())).containsEntry("status", "RUNNING").containsEntry("worker_id", "slow-heartbeat");
    }

    @Test
    void recoveryUsesTheDatabaseClock() {
        rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("w").orElseThrow();
        // Expires 1 s in the future by the database clock: not recoverable yet, whatever the JVM clock says.
        jdbc.update("UPDATE jobs SET lease_until = now() + interval '1 second' WHERE id = ?", claimed.jobId());

        assertThat(lifecycle.recoverNextExpiredLease()).isEmpty();
    }
}
