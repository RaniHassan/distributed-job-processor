package com.example.jobprocessor.job.service;

import static com.example.jobprocessor.TestRows.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.job.lifecycle.FailureType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * Claiming and fenced result recording against real PostgreSQL (README §10, §11.3). The
 * dispatcher is disabled in this context, so only these tests claim jobs; the job tables are
 * emptied before each test.
 */
@ApiIntegrationTest
class JobClaimIT {

    @Autowired
    private JobLifecycleService lifecycle;
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

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("SELECT * FROM job_attempts WHERE job_id = ? ORDER BY attempt_number", id);
    }

    // ---- claiming ---------------------------------------------------------------------------------

    @Test
    void claimTakesOwnershipAndOpensTheFirstAttempt() {
        UUID id = rows.job(owner).set("payload", "{\"report\":\"daily\",\"n\":null}").insert();
        Instant before = Instant.now();

        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();

        assertThat(claimed.jobId()).isEqualTo(id);
        assertThat(claimed.fencingToken()).isEqualTo(1);
        assertThat(claimed.workerId()).isEqualTo("worker-a");
        assertThat(claimed.payload()).containsEntry("report", "daily").containsEntry("n", null);

        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "RUNNING").containsEntry("worker_id", "worker-a")
                .containsEntry("fencing_token", 1L).containsEntry("retry_count", 0);
        Instant leaseUntil = ((Timestamp) row.get("lease_until")).toInstant();
        assertThat(Duration.between(before, leaseUntil)).isBetween(Duration.ofMillis(1500), Duration.ofSeconds(3)); // 2s lease
        assertThat(row.get("heartbeat_at")).isNotNull();
        assertThat(row.get("started_at")).isNotNull();

        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0)).containsEntry("attempt_number", 1).containsEntry("fencing_token", 1L)
                .containsEntry("worker_id", "worker-a").containsEntry("status", "RUNNING");
    }

    @Test
    void nothingToClaimWhenNoJobIsEligible() {
        rows.job(owner).set("next_retry_at", now().plusMinutes(5)).insert();
        rows.job(owner).running().insert();
        rows.job(owner).cancelRequested().insert();
        rows.job(owner).completed().insert();
        rows.job(owner).failed().insert();
        rows.job(owner).cancelled().insert();

        assertThat(lifecycle.claimNext("worker-a")).isEmpty();
    }

    @Test
    void claimsInOrderOfEligibilityThenCreation() {
        OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        UUID third = rows.job(owner).set("next_retry_at", t.plusMinutes(2)).set("created_at", t).insert();
        UUID second = rows.job(owner).set("next_retry_at", t.plusMinutes(1)).set("created_at", t.plusMinutes(1)).insert();
        UUID first = rows.job(owner).set("next_retry_at", t.plusMinutes(1)).set("created_at", t).insert();

        assertThat(List.of(claim(), claim(), claim())).containsExactly(first, second, third);
    }

    @Test
    void lockedRowsAreSkippedNotWaitedFor() throws Exception {
        OffsetDateTime t = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        UUID locked = rows.job(owner).set("created_at", t).set("next_retry_at", t).insert();
        UUID free = rows.job(owner).set("created_at", t.plusSeconds(1)).set("next_retry_at", t.plusSeconds(1)).insert();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = other.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM jobs WHERE id = ? FOR UPDATE", UUID.class, locked);
                rowLocked.countDown();
                await(release);
            }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThat(claim()).isEqualTo(free);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(lifecycle.claimNext("worker-a")).isEmpty(); // only the locked one is left

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(claim()).isEqualTo(locked);
        } finally {
            release.countDown();
            other.shutdownNow();
        }
    }

    @Test
    void concurrentClaimersNeverShareAJob() throws Exception {
        int jobs = 150;
        int claimers = 10;
        for (int i = 0; i < jobs; i++) {
            rows.job(owner).insert();
        }
        ConcurrentLinkedQueue<ClaimedJob> claims = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(claimers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int c = 0; c < claimers; c++) {
                String workerId = "worker-" + c;
                futures.add(pool.submit(() -> {
                    await(start);
                    Optional<ClaimedJob> claimed;
                    while ((claimed = lifecycle.claimNext(workerId)).isPresent()) {
                        claims.add(claimed.get());
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        Set<UUID> claimedIds = new HashSet<>();
        claims.forEach(c -> assertThat(claimedIds.add(c.jobId())).as("claimed twice: " + c.jobId()).isTrue());
        assertThat(claimedIds).hasSize(jobs);
        assertThat(claims.stream().map(ClaimedJob::workerId).distinct().count()).as("work was spread").isGreaterThan(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'RUNNING' AND fencing_token = 1", Long.class))
                .isEqualTo(jobs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_attempts WHERE attempt_number = 1 AND status = 'RUNNING'", Long.class))
                .isEqualTo(jobs);
        // Each job's owner and its attempt's worker agree.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM jobs j JOIN job_attempts a ON a.job_id = j.id
                WHERE a.worker_id <> j.worker_id OR a.fencing_token <> j.fencing_token""", Long.class)).isZero();
    }

    /**
     * README §10: the claim is one transaction. If the attempt cannot be written, the job update
     * rolls back with it: no ownership, no token increment, no half-claimed job.
     */
    @Test
    void claimIsAllOrNothing() {
        UUID id = rows.job(owner).insert();
        // A conflicting attempt row makes the claim's attempt insert fail after the job was updated.
        rows.attempt(id, 1).set("status", "FAILED").set("finished_at", now()).insert();

        assertThatThrownBy(() -> lifecycle.claimNext("worker-a"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("uq_job_attempts_job_attempt");

        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("fencing_token", 0L);
        assertThat(row.get("worker_id")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("started_at")).isNull();
        assertThat(attempts(id)).hasSize(1);
    }

    @Test
    void reclaimStartsANewGenerationAndAttempt() {
        UUID id = rows.job(owner).insert();
        ClaimedJob first = lifecycle.claimNext("worker-a").orElseThrow();
        Object startedAt = job(id).get("started_at");

        assertThat(lifecycle.releaseClaim(first, "pool rejected")).isTrue();
        Map<String, Object> released = job(id);
        assertThat(released).containsEntry("status", "PENDING").containsEntry("retry_count", 0);
        assertThat(released.get("worker_id")).isNull();
        assertThat(released.get("lease_until")).isNull();
        assertThat(released.get("heartbeat_at")).isNull();

        ClaimedJob second = lifecycle.claimNext("worker-b").orElseThrow();

        assertThat(second.fencingToken()).isEqualTo(2);
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(job(id).get("started_at")).as("started_at keeps the first claim").isEqualTo(startedAt);
        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).extracting(a -> a.get("status")).containsExactly("ABANDONED", "RUNNING");
        assertThat(attempts.get(0).get("error_message")).isEqualTo("pool rejected");
        assertThat(attempts.get(1)).containsEntry("worker_id", "worker-b").containsEntry("fencing_token", 2L);
    }

    // ---- fenced results ---------------------------------------------------------------------------

    @Test
    void completionRecordsSuccessAndReleasesOwnership() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();

        assertThat(lifecycle.complete(claimed)).isEqualTo(RecordedOutcome.COMPLETED);

        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "COMPLETED").containsEntry("fencing_token", 1L);
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("worker_id")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("heartbeat_at")).isNull();
        assertThat(attempts(id).get(0)).containsEntry("status", "SUCCEEDED");
        assertThat(attempts(id).get(0).get("finished_at")).isNotNull();
    }

    @Test
    void failureRecordsClassifiedErrorAndReleasesOwnership() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();

        assertThat(lifecycle.fail(claimed, FailureType.PERMANENT, "Invalid input")).isEqualTo(RecordedOutcome.FAILED);

        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "FAILED").containsEntry("error_message", "[PERMANENT] Invalid input");
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("worker_id")).isNull();
        assertThat(row).as("permanent failures never use a retry").containsEntry("retry_count", 0);
        assertThat(attempts(id).get(0)).containsEntry("status", "FAILED").containsEntry("error_message", "[PERMANENT] Invalid input");
    }

    // ---- retry policy (README §12; test backoff 200/400/800 ms, 3 retries) -----------------------

    @Test
    void transientFailureWithRetriesLeftIsRequeuedWithBackoff() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();

        assertThat(lifecycle.fail(claimed, FailureType.TRANSIENT, "Upstream timed out"))
                .isEqualTo(RecordedOutcome.RETRY_SCHEDULED);

        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("retry_count", 1)
                .containsEntry("error_message", "[TRANSIENT] Upstream timed out");
        assertThat(row.get("worker_id")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(row.get("heartbeat_at")).isNull();
        assertThat(row.get("finished_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT next_retry_at > now() + interval '100 milliseconds' FROM jobs WHERE id = ?",
                Boolean.class, id)).as("backoff applied").isTrue();
        assertThat(attempts(id).get(0)).containsEntry("status", "FAILED")
                .containsEntry("error_message", "[TRANSIENT] Upstream timed out");
        assertThat(lifecycle.claimNext("worker-b")).as("not claimable before the backoff").isEmpty();
    }

    @Test
    void retriesAreBoundedForTransientAndUnexpectedFailures() {
        UUID transientJob = rows.job(owner).set("retry_count", 3).insert();
        UUID unexpectedJob = rows.job(owner).set("retry_count", 3).insert();
        ClaimedJob first = lifecycle.claimNext("worker-a").orElseThrow();
        ClaimedJob second = lifecycle.claimNext("worker-a").orElseThrow();

        assertThat(lifecycle.fail(first, FailureType.TRANSIENT, "timeout")).isEqualTo(RecordedOutcome.FAILED);
        assertThat(lifecycle.fail(second, FailureType.UNEXPECTED, "Unexpected processing error")).isEqualTo(RecordedOutcome.FAILED);

        for (UUID id : List.of(transientJob, unexpectedJob)) {
            assertThat(job(id)).containsEntry("status", "FAILED").containsEntry("retry_count", 3);
        }
    }

    @Test
    void unexpectedFailureWithBudgetLeftIsRetried() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();

        assertThat(lifecycle.fail(claimed, FailureType.UNEXPECTED, "Unexpected processing error"))
                .isEqualTo(RecordedOutcome.RETRY_SCHEDULED);
        assertThat(job(id)).containsEntry("status", "PENDING").containsEntry("retry_count", 1);
    }

    @Test
    void longErrorMessagesAreTruncated() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();

        lifecycle.fail(claimed, FailureType.TRANSIENT, "x".repeat(10_000));

        String stored = (String) job(id).get("error_message");
        assertThat(stored).hasSize(JobLifecycleService.MAX_ERROR_LENGTH).startsWith("[TRANSIENT] ").endsWith("...");
    }

    @Test
    void staleOwnerCannotOverwriteANewerGeneration() {
        UUID id = rows.job(owner).insert();
        ClaimedJob stale = lifecycle.claimNext("worker-a").orElseThrow();
        lifecycle.releaseClaim(stale, "simulated loss of ownership");
        ClaimedJob current = lifecycle.claimNext("worker-b").orElseThrow();

        assertThat(lifecycle.complete(stale)).isEqualTo(RecordedOutcome.OWNERSHIP_LOST);
        assertThat(lifecycle.fail(stale, FailureType.PERMANENT, "late")).isEqualTo(RecordedOutcome.OWNERSHIP_LOST);
        assertThat(lifecycle.releaseClaim(stale, "late")).isFalse();

        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "RUNNING").containsEntry("worker_id", "worker-b").containsEntry("fencing_token", 2L);
        assertThat(attempts(id).get(1)).containsEntry("status", "RUNNING");

        assertThat(lifecycle.complete(current)).isEqualTo(RecordedOutcome.COMPLETED);
    }

    @Test
    void sameTokenFromAnotherWorkerIsRejected() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();
        ClaimedJob impostor = new ClaimedJob(id, "worker-x", claimed.fencingToken(), Map.of());

        assertThat(lifecycle.complete(impostor)).isEqualTo(RecordedOutcome.OWNERSHIP_LOST);
        assertThat(job(id)).containsEntry("status", "RUNNING").containsEntry("worker_id", "worker-a");
    }

    // ---- cancellation committed before the worker's result (README §9.2) -------------------------

    private ClaimedJob claimedWithCancellationRequest() {
        rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();
        jdbc.update("UPDATE jobs SET status = 'CANCEL_REQUESTED', cancel_requested_at = now() WHERE id = ?", claimed.jobId());
        return claimed;
    }

    private void assertCancelled(UUID id) {
        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("retry_count", 0);
        assertThat(row.get("completed_at")).as("COMPLETED is never written after cancellation").isNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("worker_id")).isNull();
        assertThat(row.get("lease_until")).isNull();
        assertThat(attempts(id).get(0)).containsEntry("status", "CANCELLED");
    }

    @Test
    void completionAfterACancellationRequestRecordsCancelled() {
        ClaimedJob claimed = claimedWithCancellationRequest();

        assertThat(lifecycle.complete(claimed)).isEqualTo(RecordedOutcome.CANCELLED);

        assertCancelled(claimed.jobId());
        assertThat(attempts(claimed.jobId()).get(0).get("error_message")).asString().contains("result discarded");
    }

    @Test
    void failureAfterACancellationRequestIsNotTreatedAsAFailure() {
        ClaimedJob claimed = claimedWithCancellationRequest();

        assertThat(lifecycle.fail(claimed, FailureType.TRANSIENT, "timeout")).isEqualTo(RecordedOutcome.CANCELLED);

        assertCancelled(claimed.jobId());
        assertThat(job(claimed.jobId()).get("error_message")).as("no retry, no failure recorded on the job").isNull();
    }

    @Test
    void workerConfirmsCancellation() {
        ClaimedJob claimed = claimedWithCancellationRequest();

        assertThat(lifecycle.confirmCancelled(claimed)).isEqualTo(RecordedOutcome.CANCELLED);

        assertCancelled(claimed.jobId());
        assertThat(lifecycle.confirmCancelled(claimed)).as("only once").isEqualTo(RecordedOutcome.OWNERSHIP_LOST);
    }

    @Test
    void cancellationConfirmationWithoutARequestWritesNothing() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();

        assertThat(lifecycle.confirmCancelled(claimed)).isEqualTo(RecordedOutcome.OWNERSHIP_LOST);
        assertThat(job(id)).containsEntry("status", "RUNNING").containsEntry("worker_id", "worker-a");
    }

    private UUID claim() {
        return lifecycle.claimNext("worker-a").orElseThrow().jobId();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
