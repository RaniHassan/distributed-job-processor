package com.example.jobprocessor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts rows with plain SQL so tests can build any database state, including the owned and
 * terminal states that the application itself only reaches through conditional updates.
 */
@TestComponent
public class TestRows {

    private final JdbcTemplate jdbc;

    public TestRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID insertUser() {
        return insertUser("user-" + UUID.randomUUID(), "USER");
    }

    public UUID insertUser(String username, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, ?, ?)",
                id, username, "$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234", role);
        return id;
    }

    /** A valid PENDING job; adjust columns with {@link Row#set} or the state helpers. */
    public Row job(UUID ownerId) {
        return new Row("jobs")
                .set("id", UUID.randomUUID())
                .set("owner_id", ownerId)
                .set("status", "PENDING")
                .set("payload", "{}")
                .set("max_retries", 3);
    }

    /** A valid RUNNING attempt for the given job. */
    public Row attempt(UUID jobId, int attemptNumber) {
        return new Row("job_attempts")
                .set("id", UUID.randomUUID())
                .set("job_id", jobId)
                .set("attempt_number", attemptNumber)
                .set("worker_id", "worker-a")
                .set("fencing_token", (long) attemptNumber)
                .set("status", "RUNNING");
    }

    public Row idempotencyKey(UUID ownerId, UUID jobId, String key) {
        return new Row("idempotency_keys")
                .set("id", UUID.randomUUID())
                .set("owner_id", ownerId)
                .set("idempotency_key", key)
                .set("request_hash", "a".repeat(64))
                .set("job_id", jobId)
                .set("expires_at", now().plusHours(24));
    }

    public final class Row {

        private final String table;
        private final Map<String, Object> values = new LinkedHashMap<>();

        private Row(String table) {
            this.table = table;
        }

        public Row set(String column, Object value) {
            values.put(column, value);
            return this;
        }

        /** Owned by a worker with a live lease. */
        public Row running() {
            return set("status", "RUNNING")
                    .set("worker_id", "worker-a")
                    .set("lease_until", now().plusSeconds(30))
                    .set("heartbeat_at", now())
                    .set("started_at", now())
                    .set("fencing_token", 1L);
        }

        public Row cancelRequested() {
            return running().set("status", "CANCEL_REQUESTED").set("cancel_requested_at", now());
        }

        public Row completed() {
            return set("status", "COMPLETED")
                    .set("started_at", now())
                    .set("completed_at", now())
                    .set("finished_at", now())
                    .set("fencing_token", 1L);
        }

        public Row failed() {
            return set("status", "FAILED")
                    .set("started_at", now())
                    .set("finished_at", now())
                    .set("fencing_token", 1L)
                    .set("error_message", "boom");
        }

        public Row cancelled() {
            return set("status", "CANCELLED").set("finished_at", now());
        }

        public UUID insert() {
            List<String> placeholders = new ArrayList<>();
            for (String column : values.keySet()) {
                placeholders.add(column.equals("payload") ? "CAST(? AS jsonb)" : "?");
            }
            String sql = "INSERT INTO " + table + " (" + String.join(", ", values.keySet()) + ") VALUES ("
                    + String.join(", ", placeholders) + ")";
            jdbc.update(sql, values.values().toArray());
            return (UUID) values.get("id");
        }
    }
}
