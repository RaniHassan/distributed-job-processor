package com.example.jobprocessor.job.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A job row. JPA only inserts and reads jobs: every column is mapped {@code updatable = false} and
 * the entity has no setters, so Hibernate never issues an UPDATE for it. Every state change is a
 * conditional JDBC update owned by Job Management, which validates ownership, fencing token and
 * the legal transition in the same statement. Read a fresh instance after such an update; an
 * instance already loaded is a snapshot.
 *
 * <p>Not {@code @Immutable}: Hibernate cannot read back database-generated columns
 * ({@code created_at}, {@code next_retry_at}) for immutable entities.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private JobStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    // Timestamps with database defaults are written by PostgreSQL (one clock for all instances)
    // and read back after insert.
    @Generated
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "started_at", updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at", updatable = false)
    private Instant completedAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    @Column(name = "retry_count", nullable = false, updatable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false, updatable = false)
    private int maxRetries;

    @Generated
    @Column(name = "next_retry_at", insertable = false, updatable = false)
    private Instant nextRetryAt;

    @Column(name = "worker_id", updatable = false, length = 128)
    private String workerId;

    @Column(name = "lease_until", updatable = false)
    private Instant leaseUntil;

    @Column(name = "heartbeat_at", updatable = false)
    private Instant heartbeatAt;

    @Column(name = "fencing_token", nullable = false, updatable = false)
    private long fencingToken;

    @Column(name = "cancel_requested_at", updatable = false)
    private Instant cancelRequestedAt;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    protected Job() {
    }

    /**
     * A new job, immediately eligible for processing: next_retry_at defaults to the database's
     * now() on insert.
     */
    public static Job newPending(UUID ownerId, Map<String, Object> payload, int maxRetries) {
        Job job = new Job();
        job.ownerId = ownerId;
        job.status = JobStatus.PENDING;
        job.payload = new LinkedHashMap<>(payload);
        job.retryCount = 0;
        job.maxRetries = maxRetries;
        job.fencingToken = 0;
        return job;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Map<String, Object> getPayload() {
        // Not Map.copyOf: JSON payloads may legitimately contain null values.
        return Collections.unmodifiableMap(payload);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public long getFencingToken() {
        return fencingToken;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
