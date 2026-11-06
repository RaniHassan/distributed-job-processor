package com.example.jobprocessor.job.repository;

import com.example.jobprocessor.job.domain.AttemptStatus;
import com.example.jobprocessor.job.domain.JobStatus;
import java.time.Duration;
import java.util.Optional;
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
                        RETURNING j.id, j.fencing_token, j.payload::text AS payload
                        """,
                        (rs, i) -> new ClaimRow(rs.getObject("id", UUID.class), rs.getLong("fencing_token"),
                                rs.getString("payload")),
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

    public record ClaimRow(UUID jobId, long fencingToken, String payloadJson) {
    }
}
