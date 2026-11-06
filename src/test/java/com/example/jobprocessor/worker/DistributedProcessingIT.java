package com.example.jobprocessor.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobprocessor.ProcessingIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.worker.execution.ActiveExecutions;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import com.example.jobprocessor.worker.pool.WorkerPool;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Leases, heartbeats, fencing, cancellation detection and recovery working together with the real
 * background threads (README §11, §13). Test timings: lease 2s, heartbeat 500 ms, cancellation
 * check 100 ms, recovery every 500 ms, retry backoff 200/400/800 ms.
 */
@ProcessingIntegrationTest
class DistributedProcessingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestUsers users;
    @Autowired
    private TestRows rows;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WorkerPool pool;
    @Autowired
    private ActiveExecutions executions;
    @Autowired
    private WorkerIdentity identity;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private TestUser alice;

    @BeforeEach
    void setUp() {
        awaitIdle();
        rows.deleteAllJobs();
        alice = users.create(Role.USER);
    }

    @AfterEach
    void drain() {
        awaitIdle();
    }

    private void awaitIdle() {
        await().atMost(TIMEOUT).until(() -> pool.activeCount() == 0 && executions.size() == 0);
    }

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("SELECT * FROM job_attempts WHERE job_id = ? ORDER BY attempt_number", id);
    }

    private String jobStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id);
    }

    private UUID pendingJob(int durationMs) {
        return rows.job(alice.id()).set("payload", "{\"simulation\":{\"durationMs\":" + durationMs + "}}").insert();
    }

    @Test
    void heartbeatKeepsAJobLongerThanItsLeaseAlive() {
        UUID id = pendingJob(5000); // 2.5x the lease
        await().atMost(TIMEOUT).until(() -> "RUNNING".equals(jobStatus(id)));

        Set<Timestamp> heartbeats = new HashSet<>();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100)).until(() -> {
            Map<String, Object> row = job(id);
            if ("RUNNING".equals(row.get("status"))) {
                Boolean leaseValid = jdbc.queryForObject("SELECT lease_until > now() FROM jobs WHERE id = ?", Boolean.class, id);
                assertThat(leaseValid).as("lease never lapses while the worker is healthy").isTrue();
                heartbeats.add((Timestamp) row.get("heartbeat_at"));
            }
            return "COMPLETED".equals(row.get("status"));
        });

        assertThat(heartbeats).as("lease renewed repeatedly").hasSizeGreaterThanOrEqualTo(5);
        assertThat(job(id)).containsEntry("fencing_token", 1L).containsEntry("retry_count", 0);
        assertThat(attempts(id)).extracting(a -> a.get("status")).containsExactly("SUCCEEDED");
    }

    /** A crashed instance leaves an owned job whose lease runs out; this instance recovers and runs it. */
    @Test
    void jobOfACrashedInstanceIsRecoveredAndCompletedHere() {
        double requeuedBefore = meters.find("lease.expiration").tag("outcome", "REQUEUED").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        UUID id = rows.job(alice.id()).set("payload", "{\"simulation\":{\"durationMs\":500}}").running()
                .set("worker_id", "crashed-instance-1").set("lease_until", TestRows.now().plusSeconds(1)).insert();
        rows.attempt(id, 1).set("worker_id", "crashed-instance-1").insert();

        await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(jobStatus(id)));

        assertThat(job(id)).containsEntry("retry_count", 1).containsEntry("fencing_token", 2L);
        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).extracting(a -> a.get("status")).containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(attempts.get(0)).containsEntry("worker_id", "crashed-instance-1");
        assertThat(attempts.get(1)).containsEntry("worker_id", identity.workerId());
        assertThat(meters.get("lease.expiration").tag("outcome", "REQUEUED").counter().count() - requeuedBefore)
                .as("recovery is visible in lease_expiration_total").isEqualTo(1);
    }

    @Test
    void crashedJobWithoutRetriesLeftFails() {
        UUID id = rows.job(alice.id()).running().set("retry_count", 3).set("max_retries", 3)
                .set("worker_id", "crashed-instance-2").set("lease_until", TestRows.now().minusSeconds(1)).insert();
        rows.attempt(id, 1).set("worker_id", "crashed-instance-2").insert();

        await().atMost(TIMEOUT).until(() -> "FAILED".equals(jobStatus(id)));

        assertThat(job(id)).containsEntry("error_message", "[ABANDONED] Processing was interrupted; no retries left");
        assertThat(attempts(id)).extracting(a -> a.get("status")).containsExactly("ABANDONED");
    }

    /** Ownership taken over elsewhere (e.g. after a long pause): the stale worker stops and writes nothing. */
    @Test
    void workerThatLosesOwnershipStopsWithoutWriting() {
        UUID id = pendingJob(5000);
        await().atMost(TIMEOUT).until(() -> "RUNNING".equals(jobStatus(id)));

        long takeoverAt = System.nanoTime();
        jdbc.update("""
                UPDATE jobs SET worker_id = 'other-instance', fencing_token = fencing_token + 1,
                                lease_until = now() + interval '1 hour'
                WHERE id = ?""", id);

        await().atMost(Duration.ofSeconds(3)).until(() -> pool.activeCount() == 0);
        assertThat(Duration.ofNanos(System.nanoTime() - takeoverAt))
                .as("stopped by the next heartbeat, long before the 5s job would end").isLessThan(Duration.ofSeconds(2));

        // Even after the job would have finished, the stale worker recorded nothing.
        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(5)).until(() -> true);
        Map<String, Object> row = job(id);
        assertThat(row).containsEntry("status", "RUNNING").containsEntry("worker_id", "other-instance")
                .containsEntry("fencing_token", 2L);
        assertThat(row.get("completed_at")).isNull();
        assertThat(attempts(id).get(0)).containsEntry("status", "RUNNING").containsEntry("fencing_token", 1L);
    }

    /**
     * Cooperative cancellation (README §9.1): the request is made through the API, the worker's
     * instance detects it in PostgreSQL within one check interval, the worker stops and records
     * CANCELLED itself. (If the worker had died instead, Recovery would finalize it: RecoveryIT.)
     */
    @Test
    void cancellingARunningJobStopsItsWorkerWhichConfirmsTheCancellation() throws Exception {
        UUID id = pendingJob(6000);
        await().atMost(TIMEOUT).until(() -> "RUNNING".equals(jobStatus(id)));

        long cancelledAt = System.nanoTime();
        mvc.perform(delete("/api/v1/jobs/" + id).header(HttpHeaders.AUTHORIZATION, users.bearer(alice)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));

        await().atMost(Duration.ofSeconds(2)).until(() -> "CANCELLED".equals(jobStatus(id)));
        assertThat(Duration.ofNanos(System.nanoTime() - cancelledAt))
                .as("stopped and confirmed by the worker, not by lease expiry").isLessThan(Duration.ofSeconds(1));

        Map<String, Object> row = job(id);
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("worker_id")).isNull();
        assertThat(row).containsEntry("retry_count", 0);
        assertThat(attempts(id)).extracting(a -> a.get("status")).containsExactly("CANCELLED");
        await().atMost(Duration.ofSeconds(2)).until(() -> pool.activeCount() == 0);
    }

    @Test
    void manyJobsCompleteWithoutAnyFalseRecovery() {
        for (int i = 0; i < 10; i++) {
            pendingJob(2500); // longer than the lease, so every one depends on heartbeats
        }

        await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'", Integer.class) == 10);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_attempts WHERE status <> 'SUCCEEDED'", Integer.class))
                .as("no healthy job was abandoned or retried").isZero();
        assertThat(jdbc.queryForObject("SELECT max(fencing_token) FROM jobs", Long.class)).isEqualTo(1L);
    }
}
