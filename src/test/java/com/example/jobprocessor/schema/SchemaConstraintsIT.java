package com.example.jobprocessor.schema;

import static com.example.jobprocessor.TestRows.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.PostgresRepositoryTest;
import com.example.jobprocessor.TestRows;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every rule the database enforces, checked against real PostgreSQL. */
@PostgresRepositoryTest
class SchemaConstraintsIT {

    @Autowired
    private TestRows rows;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID ownerId;

    @BeforeEach
    void createOwner() {
        ownerId = rows.insertUser();
    }

    private void assertViolates(String constraint, TestRows.Row row) {
        assertThatThrownBy(row::insert)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Nested
    class Jobs {

        static Stream<Arguments> validStates() {
            return Stream.of(
                    Arguments.of("PENDING", (UnaryOperator<TestRows.Row>) r -> r),
                    Arguments.of("RUNNING", (UnaryOperator<TestRows.Row>) TestRows.Row::running),
                    Arguments.of("RUNNING before first heartbeat",
                            (UnaryOperator<TestRows.Row>) r -> r.running().set("heartbeat_at", null)),
                    Arguments.of("CANCEL_REQUESTED", (UnaryOperator<TestRows.Row>) TestRows.Row::cancelRequested),
                    Arguments.of("COMPLETED", (UnaryOperator<TestRows.Row>) TestRows.Row::completed),
                    Arguments.of("FAILED", (UnaryOperator<TestRows.Row>) TestRows.Row::failed),
                    Arguments.of("CANCELLED before any claim", (UnaryOperator<TestRows.Row>) TestRows.Row::cancelled),
                    Arguments.of("CANCELLED after running",
                            (UnaryOperator<TestRows.Row>) r -> r.cancelled().set("started_at", now())
                                    .set("cancel_requested_at", now()).set("fencing_token", 2L)),
                    Arguments.of("PENDING awaiting retry",
                            (UnaryOperator<TestRows.Row>) r -> r.set("retry_count", 2).set("started_at", now())
                                    .set("fencing_token", 2L).set("next_retry_at", now().plusSeconds(10))),
                    Arguments.of("retries exhausted",
                            (UnaryOperator<TestRows.Row>) r -> r.failed().set("retry_count", 3)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("validStates")
        void acceptsEveryConsistentState(String name, UnaryOperator<TestRows.Row> state) {
            assertThatCode(() -> state.apply(rows.job(ownerId)).insert()).doesNotThrowAnyException();
        }

        @Test
        void newJobIsImmediatelyEligibleAndUnowned() {
            UUID id = rows.job(ownerId).insert();

            Boolean eligible = jdbc.queryForObject(
                    "SELECT next_retry_at <= now() AND fencing_token = 0 AND retry_count = 0 FROM jobs WHERE id = ?",
                    Boolean.class, id);
            assertThat(eligible).isTrue();
        }

        @Test
        void rejectsUnknownStatus() {
            assertViolates("ck_jobs_status", rows.job(ownerId).set("status", "PAUSED"));
        }

        @Test
        void rejectsNonObjectPayload() {
            assertViolates("ck_jobs_payload_object", rows.job(ownerId).set("payload", "[1, 2]"));
        }

        @Test
        void rejectsMissingPayload() {
            assertViolates("payload", rows.job(ownerId).set("payload", null));
        }

        @Test
        void rejectsUnknownOwner() {
            assertViolates("jobs_owner_id_fkey", rows.job(UUID.randomUUID()));
        }

        @Test
        void rejectsNegativeMaxRetries() {
            assertViolates("ck_jobs_max_retries", rows.job(ownerId).set("max_retries", -1));
        }

        @Test
        void rejectsNegativeRetryCount() {
            assertViolates("ck_jobs_retry_count", rows.job(ownerId).set("retry_count", -1));
        }

        @Test
        void rejectsRetryCountAboveMaximum() {
            assertViolates("ck_jobs_retry_count", rows.job(ownerId).set("retry_count", 4).set("max_retries", 3));
        }

        @Test
        void rejectsNegativeFencingToken() {
            assertViolates("ck_jobs_fencing_token", rows.job(ownerId).set("fencing_token", -1L));
        }

        @Test
        void rejectsRunningWithoutWorker() {
            assertViolates("ck_jobs_ownership", rows.job(ownerId).running().set("worker_id", null));
        }

        @Test
        void rejectsRunningWithoutLease() {
            assertViolates("ck_jobs_ownership", rows.job(ownerId).running().set("lease_until", null));
        }

        @Test
        void rejectsCancelRequestedWithoutLease() {
            assertViolates("ck_jobs_ownership", rows.job(ownerId).cancelRequested().set("lease_until", null));
        }

        @Test
        void rejectsPendingWithWorker() {
            assertViolates("ck_jobs_ownership", rows.job(ownerId).set("worker_id", "worker-a"));
        }

        @Test
        void rejectsPendingWithLease() {
            assertViolates("ck_jobs_ownership", rows.job(ownerId).set("lease_until", now()));
        }

        @Test
        void rejectsPendingWithHeartbeat() {
            assertViolates("ck_jobs_ownership", rows.job(ownerId).set("heartbeat_at", now()));
        }

        @Test
        void rejectsTerminalJobThatStillLooksOwned() {
            assertViolates("ck_jobs_ownership",
                    rows.job(ownerId).completed().set("worker_id", "worker-a").set("lease_until", now()));
        }

        @Test
        void rejectsCancelRequestedWithoutRequestTime() {
            assertViolates("ck_jobs_cancel_requested_at",
                    rows.job(ownerId).cancelRequested().set("cancel_requested_at", null));
        }

        @Test
        void rejectsCompletedWithoutCompletedAt() {
            assertViolates("ck_jobs_completed_at", rows.job(ownerId).completed().set("completed_at", null));
        }

        @Test
        void rejectsCompletedAtOnFailedJob() {
            assertViolates("ck_jobs_completed_at", rows.job(ownerId).failed().set("completed_at", now()));
        }

        @Test
        void rejectsCompletedAtOnCancelledJob() {
            assertViolates("ck_jobs_completed_at", rows.job(ownerId).cancelled().set("completed_at", now()));
        }

        @Test
        void rejectsTerminalJobWithoutFinishedAt() {
            assertViolates("ck_jobs_finished_at", rows.job(ownerId).failed().set("finished_at", null));
        }

        @Test
        void rejectsFinishedAtOnActiveJob() {
            assertViolates("ck_jobs_finished_at", rows.job(ownerId).running().set("finished_at", now()));
        }

        @Test
        void rejectsRunningWithoutStartedAt() {
            assertViolates("ck_jobs_started_at", rows.job(ownerId).running().set("started_at", null));
        }

        @Test
        void rejectsFailedWithoutStartedAt() {
            assertViolates("ck_jobs_started_at", rows.job(ownerId).failed().set("started_at", null));
        }

        @Test
        void updateTriggerMaintainsUpdatedAt() {
            OffsetDateTime old = OffsetDateTime.parse("2020-01-01T00:00:00Z");
            UUID id = rows.job(ownerId).set("created_at", old).set("updated_at", old).insert();

            jdbc.update("UPDATE jobs SET error_message = 'x' WHERE id = ?", id);

            Timestamp updatedAt = jdbc.queryForObject("SELECT updated_at FROM jobs WHERE id = ?", Timestamp.class, id);
            assertThat(updatedAt.toInstant()).isAfter(old.toInstant());
        }

        @Test
        void constraintsAlsoGuardUpdates() {
            UUID id = rows.job(ownerId).running().insert();

            // A transition that forgets to clear ownership fields must fail.
            assertThatThrownBy(() -> jdbc.update("UPDATE jobs SET status = 'PENDING' WHERE id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_jobs_ownership");
        }
    }

    @Nested
    class Attempts {

        private UUID jobId;

        @BeforeEach
        void createJob() {
            jobId = rows.job(ownerId).running().insert();
        }

        @Test
        void acceptsRunningAndFinishedAttempts() {
            assertThatCode(() -> {
                rows.attempt(jobId, 1).set("status", "ABANDONED").set("finished_at", now()).insert();
                rows.attempt(jobId, 2).insert();
            }).doesNotThrowAnyException();
        }

        @Test
        void rejectsSecondRunningAttemptForSameJob() {
            rows.attempt(jobId, 1).insert();

            assertViolates("uq_job_attempts_one_running", rows.attempt(jobId, 2));
        }

        @Test
        void allowsRunningAttemptsOfDifferentJobs() {
            UUID otherJob = rows.job(ownerId).running().insert();
            rows.attempt(jobId, 1).insert();

            assertThatCode(() -> rows.attempt(otherJob, 1).insert()).doesNotThrowAnyException();
        }

        @Test
        void rejectsDuplicateAttemptNumber() {
            rows.attempt(jobId, 1).set("status", "FAILED").set("finished_at", now()).insert();

            assertViolates("uq_job_attempts_job_attempt",
                    rows.attempt(jobId, 1).set("status", "FAILED").set("finished_at", now()));
        }

        @Test
        void rejectsAttemptNumberBelowOne() {
            assertViolates("ck_job_attempts_attempt_number", rows.attempt(jobId, 0).set("fencing_token", 1L));
        }

        @Test
        void rejectsFencingTokenBelowOne() {
            assertViolates("ck_job_attempts_fencing_token", rows.attempt(jobId, 1).set("fencing_token", 0L));
        }

        @Test
        void rejectsUnknownStatus() {
            assertViolates("ck_job_attempts_status",
                    rows.attempt(jobId, 1).set("status", "PENDING").set("finished_at", now()));
        }

        @Test
        void rejectsRunningAttemptWithFinishedAt() {
            assertViolates("ck_job_attempts_finished_at", rows.attempt(jobId, 1).set("finished_at", now()));
        }

        @Test
        void rejectsFinishedAttemptWithoutFinishedAt() {
            assertViolates("ck_job_attempts_finished_at", rows.attempt(jobId, 1).set("status", "SUCCEEDED"));
        }

        @Test
        void rejectsUnknownJob() {
            assertViolates("job_attempts_job_id_fkey", rows.attempt(UUID.randomUUID(), 1));
        }
    }

    @Nested
    class IdempotencyKeys {

        private UUID jobId;

        @BeforeEach
        void createJob() {
            jobId = rows.job(ownerId).insert();
        }

        @Test
        void rejectsSameKeyTwiceForSameOwner() {
            rows.idempotencyKey(ownerId, jobId, "key-1").insert();

            assertViolates("uq_idempotency_owner_key", rows.idempotencyKey(ownerId, jobId, "key-1"));
        }

        @Test
        void allowsSameKeyForDifferentOwners() {
            UUID otherOwner = rows.insertUser();
            UUID otherJob = rows.job(otherOwner).insert();
            rows.idempotencyKey(ownerId, jobId, "key-1").insert();

            assertThatCode(() -> rows.idempotencyKey(otherOwner, otherJob, "key-1").insert())
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsKeyOfMaximumLength() {
            assertThatCode(() -> rows.idempotencyKey(ownerId, jobId, "k".repeat(255)).insert())
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsKeyLongerThan255Characters() {
            assertThatThrownBy(() -> rows.idempotencyKey(ownerId, jobId, "k".repeat(256)).insert())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("value too long");
        }

        @Test
        void rejectsBlankKey() {
            assertViolates("ck_idempotency_key_not_blank", rows.idempotencyKey(ownerId, jobId, "   "));
        }

        @Test
        void rejectsExpiryNotAfterCreation() {
            assertViolates("ck_idempotency_expiry",
                    rows.idempotencyKey(ownerId, jobId, "key-1").set("expires_at", now().minusSeconds(1)));
        }

        @Test
        void rejectsUnknownJob() {
            assertViolates("idempotency_keys_job_id_fkey",
                    rows.idempotencyKey(ownerId, UUID.randomUUID(), "key-1"));
        }
    }

    @Nested
    class Users {

        @Test
        void rejectsDuplicateUsername() {
            rows.insertUser("alice", "USER");

            assertThatThrownBy(() -> rows.insertUser("alice", "ADMIN"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_users_username");
        }

        @Test
        void rejectsUnknownRole() {
            assertThatThrownBy(() -> rows.insertUser("bob", "ROOT"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_users_role");
        }
    }
}
