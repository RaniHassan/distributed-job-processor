package com.example.jobprocessor.job.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Atomic idempotency-key operations. Expiry is always evaluated with the database clock: a key is
 * live while {@code expires_at > now()} and reusable once {@code expires_at <= now()}.
 */
@Repository
public class IdempotencyKeyStore {

    private final JdbcTemplate jdbc;

    public IdempotencyKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredKey> findLive(UUID ownerId, String key) {
        return jdbc.query("""
                        SELECT job_id, request_hash FROM idempotency_keys
                        WHERE owner_id = ? AND idempotency_key = ? AND expires_at > now()
                        """,
                        (rs, i) -> new StoredKey(rs.getObject("job_id", UUID.class), rs.getString("request_hash")),
                        ownerId, key)
                .stream().findFirst();
    }

    /**
     * Binds the key to {@code jobId}: inserts it, or takes over an expired row for the same key
     * (the cleanup job may not have deleted it yet). Returns {@code false} when a live key already
     * exists. A concurrent insert of the same key blocks on the unique index until the other
     * transaction finishes, so exactly one request can bind a key.
     */
    public boolean bindKey(UUID ownerId, String key, String requestHash, UUID jobId, Duration retention) {
        int rows = jdbc.update("""
                INSERT INTO idempotency_keys (id, owner_id, idempotency_key, request_hash, job_id, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, now(), now() + make_interval(secs => CAST(? AS double precision)))
                ON CONFLICT ON CONSTRAINT uq_idempotency_owner_key DO UPDATE
                    SET request_hash = EXCLUDED.request_hash,
                        job_id = EXCLUDED.job_id,
                        created_at = EXCLUDED.created_at,
                        expires_at = EXCLUDED.expires_at
                    WHERE idempotency_keys.expires_at <= now()
                """,
                UUID.randomUUID(), ownerId, key, requestHash, jobId, retention.toMillis() / 1000.0);
        return rows == 1;
    }

    /** Deletes up to {@code batchSize} expired keys; rows locked by a concurrent reuse are skipped. */
    public int deleteExpired(int batchSize) {
        return jdbc.update("""
                DELETE FROM idempotency_keys
                WHERE id IN (
                    SELECT id FROM idempotency_keys
                    WHERE expires_at <= now()
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                """, batchSize);
    }

    public record StoredKey(UUID jobId, String requestHash) {
    }
}
