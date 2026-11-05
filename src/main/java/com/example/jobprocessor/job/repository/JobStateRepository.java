package com.example.jobprocessor.job.repository;

import com.example.jobprocessor.job.domain.JobStatus;
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
}
