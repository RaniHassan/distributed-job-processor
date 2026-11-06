package com.example.jobprocessor.job.repository;

import com.example.jobprocessor.job.domain.AttemptStatus;
import com.example.jobprocessor.job.domain.JobStatus;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Conditional job state transitions in plain SQL. Each update names the expected current status
 * in its WHERE clause and returns the number of rows changed, so a transition that lost a race
 * changes nothing. Only Job Management calls this repository.
 */
@Repository
public class JobStateRepository {

    private final JdbcTemplate jdbc;

    public JobStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the job row (waiting for any in-flight claim) and returns its status if the owner matches. */
    public Optional<JobStatus> lockStatusForOwner(UUID jobId, UUID ownerId) {
        return jdbc.query("SELECT status FROM jobs WHERE id = ? AND owner_id = ? FOR UPDATE",
                        (rs, i) -> JobStatus.valueOf(rs.getString("status")), jobId, ownerId)
                .stream().findFirst();
    }

    /** PENDING -> CANCELLED. A pending job has no owner fields to clear. */
    public int cancelPending(UUID jobId) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'CANCELLED', cancel_requested_at = now(), finished_at = now()
                WHERE id = ? AND status = 'PENDING'
                """, jobId);
    }

    /** RUNNING -> CANCEL_REQUESTED. The worker keeps ownership until it stops or its lease expires. */
    public int requestCancellation(UUID jobId) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'CANCEL_REQUESTED', cancel_requested_at = now()
                WHERE id = ? AND status = 'RUNNING'
                """, jobId);
    }

    /**
     * PENDING -> RUNNING for the oldest eligible job (README §10). The candidate row is locked with
     * {@code FOR UPDATE SKIP LOCKED}, so concurrent claimers on any instance skip it instead of
     * waiting or taking it too. The fencing token is incremented to start a new ownership
     * generation; {@code started_at} keeps the time of the first claim.
     */
    public Optional<ClaimRow> claimNext(String workerId, Duration lease) {
        return jdbc.query("""
                        WITH candidate AS (
                            SELECT id FROM jobs
                            WHERE status = 'PENDING' AND next_retry_at <= now()
                            ORDER BY next_retry_at, created_at
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        UPDATE jobs j
                        SET status = 'RUNNING',
                            worker_id = ?,
                            fencing_token = j.fencing_token + 1,
                            lease_until = now() + make_interval(secs => CAST(? AS double precision)),
                            heartbeat_at = now(),
                            started_at = COALESCE(j.started_at, now())
                        FROM candidate
                        WHERE j.id = candidate.id
                        RETURNING j.id, j.fencing_token, j.payload::text AS payload,
                                  (EXTRACT(EPOCH FROM now() - j.next_retry_at) * 1000)::bigint AS queue_wait_ms
                        """,
                        (rs, i) -> new ClaimRow(rs.getObject("id", UUID.class), rs.getLong("fencing_token"),
                                rs.getString("payload"), Duration.ofMillis(rs.getLong("queue_wait_ms"))),
                        workerId, lease.toMillis() / 1000.0)
                .stream().findFirst();
    }

    public void insertRunningAttempt(UUID jobId, long fencingToken, String workerId) {
        jdbc.update("""
                INSERT INTO job_attempts (id, job_id, attempt_number, worker_id, fencing_token, status, started_at)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', now())
                """, UUID.randomUUID(), jobId, Math.toIntExact(fencingToken), workerId, fencingToken);
    }

    /** RUNNING -> COMPLETED, only for the current owner; releases ownership. */
    public int complete(UUID jobId, String workerId, long fencingToken) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'COMPLETED', completed_at = now(), finished_at = now(),
                    worker_id = NULL, lease_until = NULL, heartbeat_at = NULL
                WHERE id = ? AND worker_id = ? AND fencing_token = ? AND status = 'RUNNING'
                """, jobId, workerId, fencingToken);
    }

    /** RUNNING -> FAILED, only for the current owner; releases ownership. */
    public int fail(UUID jobId, String workerId, long fencingToken, String errorMessage) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'FAILED', finished_at = now(), error_message = ?,
                    worker_id = NULL, lease_until = NULL, heartbeat_at = NULL
                WHERE id = ? AND worker_id = ? AND fencing_token = ? AND status = 'RUNNING'
                """, errorMessage, jobId, workerId, fencingToken);
    }

    /** RUNNING -> PENDING without consuming a retry, only for the current owner. */
    public int release(UUID jobId, String workerId, long fencingToken) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'PENDING', worker_id = NULL, lease_until = NULL, heartbeat_at = NULL
                WHERE id = ? AND worker_id = ? AND fencing_token = ? AND status = 'RUNNING'
                """, jobId, workerId, fencingToken);
    }

    /** Closes the attempt of one ownership generation; an attempt is closed at most once. */
    public int closeAttempt(UUID jobId, long fencingToken, AttemptStatus status, String errorMessage) {
        return jdbc.update("""
                UPDATE job_attempts
                SET status = ?, finished_at = now(), error_message = ?
                WHERE job_id = ? AND fencing_token = ? AND status = 'RUNNING'
                """, status.name(), errorMessage, jobId, fencingToken);
    }

    // ---- leases (README §11) ------------------------------------------------------------------

    /**
     * Heartbeat: extends the lease of a RUNNING job for its current owner only. A CANCEL_REQUESTED
     * job is deliberately not renewed, so a worker that does not stop is cut off by lease expiry.
     */
    public int renewLease(UUID jobId, String workerId, long fencingToken, Duration lease) {
        return jdbc.update("""
                UPDATE jobs
                SET lease_until = now() + make_interval(secs => CAST(? AS double precision)), heartbeat_at = now()
                WHERE id = ? AND worker_id = ? AND fencing_token = ? AND status = 'RUNNING'
                """, lease.toMillis() / 1000.0, jobId, workerId, fencingToken);
    }

    /**
     * Locks the job if this exact ownership generation still holds it, waiting for any concurrent
     * cancellation request to commit, and returns what the result decision depends on.
     */
    public Optional<OwnedJob> lockOwned(UUID jobId, String workerId, long fencingToken) {
        return jdbc.query("""
                        SELECT status, retry_count, max_retries FROM jobs
                        WHERE id = ? AND worker_id = ? AND fencing_token = ?
                        FOR UPDATE
                        """,
                        (rs, i) -> new OwnedJob(JobStatus.valueOf(rs.getString("status")), rs.getInt("retry_count"),
                                rs.getInt("max_retries")),
                        jobId, workerId, fencingToken)
                .stream().findFirst();
    }

    /** The job's status if this exact ownership generation still holds it. */
    public Optional<JobStatus> statusIfOwned(UUID jobId, String workerId, long fencingToken) {
        return jdbc.query("SELECT status FROM jobs WHERE id = ? AND worker_id = ? AND fencing_token = ?",
                        (rs, i) -> JobStatus.valueOf(rs.getString("status")), jobId, workerId, fencingToken)
                .stream().findFirst();
    }

    /** Which of the given (job, token) generations owned by {@code workerId} have a cancellation request. */
    public Set<UUID> cancellationRequested(String workerId, List<UUID> jobIds, List<Long> fencingTokens) {
        return new HashSet<>(jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT j.id FROM jobs j
                    JOIN unnest(?::uuid[], ?::bigint[]) AS owned(id, token)
                      ON j.id = owned.id AND j.fencing_token = owned.token
                    WHERE j.worker_id = ? AND j.status = 'CANCEL_REQUESTED'
                    """);
            statement.setArray(1, connection.createArrayOf("uuid", jobIds.toArray()));
            statement.setArray(2, connection.createArrayOf("bigint", fencingTokens.toArray()));
            statement.setString(3, workerId);
            return statement;
        }, (rs, i) -> rs.getObject("id", UUID.class)));
    }

    // ---- recovery (README §13) ----------------------------------------------------------------

    /**
     * Locks the job whose lease expired longest ago. SKIP LOCKED keeps concurrent Recovery Managers
     * from taking the same job, and PostgreSQL re-checks the WHERE clause after acquiring the lock,
     * so a lease renewed in the meantime is no longer selected.
     */
    public Optional<ExpiredLease> lockNextExpiredLease() {
        return jdbc.query("""
                        SELECT id, status, worker_id, fencing_token, retry_count, max_retries
                        FROM jobs
                        WHERE status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until < now()
                        ORDER BY lease_until
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """,
                        (rs, i) -> new ExpiredLease(rs.getObject("id", UUID.class), JobStatus.valueOf(rs.getString("status")),
                                rs.getString("worker_id"), rs.getLong("fencing_token"), rs.getInt("retry_count"),
                                rs.getInt("max_retries")))
                .stream().findFirst();
    }

    /** RUNNING -> PENDING for another attempt: consumes one retry and clears ownership. */
    public int requeue(UUID jobId, String workerId, long fencingToken, Duration delay, String errorMessage) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'PENDING', retry_count = retry_count + 1,
                    next_retry_at = now() + make_interval(secs => CAST(? AS double precision)),
                    error_message = ?, worker_id = NULL, lease_until = NULL, heartbeat_at = NULL
                WHERE id = ? AND worker_id = ? AND fencing_token = ? AND status = 'RUNNING'
                """, delay.toMillis() / 1000.0, errorMessage, jobId, workerId, fencingToken);
    }

    /** CANCEL_REQUESTED -> CANCELLED for the given ownership generation; clears ownership. */
    public int confirmCancelled(UUID jobId, String workerId, long fencingToken) {
        return jdbc.update("""
                UPDATE jobs
                SET status = 'CANCELLED', finished_at = now(),
                    worker_id = NULL, lease_until = NULL, heartbeat_at = NULL
                WHERE id = ? AND worker_id = ? AND fencing_token = ? AND status = 'CANCEL_REQUESTED'
                """, jobId, workerId, fencingToken);
    }

    /** Number of jobs per not-yet-finished status, across the whole cluster. */
    public Map<JobStatus, Long> countActiveJobs() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        jdbc.query("""
                SELECT status, count(*) AS jobs FROM jobs
                WHERE status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                GROUP BY status
                """, rs -> {
            counts.put(JobStatus.valueOf(rs.getString("status")), rs.getLong("jobs"));
        });
        return counts;
    }

    /** @param queueWait time between the job becoming eligible ({@code next_retry_at}) and this claim */
    public record ClaimRow(UUID jobId, long fencingToken, String payloadJson, Duration queueWait) {
    }

    public record OwnedJob(JobStatus status, int retryCount, int maxRetries) {
    }

    public record ExpiredLease(UUID jobId, JobStatus status, String workerId, long fencingToken,
                               int retryCount, int maxRetries) {
    }
}
