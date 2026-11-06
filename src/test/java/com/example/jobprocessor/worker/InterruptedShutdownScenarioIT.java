package com.example.jobprocessor.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.common.config.JobProcessorProperties.Dispatcher;
import com.example.jobprocessor.common.config.JobProcessorProperties.Shutdown;
import com.example.jobprocessor.common.config.JobProcessorProperties.Worker;
import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.RecoveredJob;
import com.example.jobprocessor.recovery.service.RecoveryManager;
import com.example.jobprocessor.worker.dispatcher.JobDispatcher;
import com.example.jobprocessor.worker.execution.JobRunner;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import com.example.jobprocessor.worker.pool.WorkerPool;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reproduces the Docker scenario from Phase 4 verification end to end: an instance is shut down
 * while its workers are mid-job. The dispatcher stops, the pool's drain timeout runs out, workers
 * are interrupted and record nothing, so the jobs stay RUNNING under the stopped worker until
 * their leases expire. The Recovery Manager then takes them over and a second instance completes
 * them as a new ownership generation.
 *
 * <p>Private dispatchers and pools are built here ("instances" with their own worker ids) so they
 * can be stopped without affecting the shared application context, whose own dispatcher and
 * recovery scheduler are disabled. Heartbeats come from the context's {@code LeaseHeartbeat}.
 */
@ApiIntegrationTest
class InterruptedShutdownScenarioIT {

    private static final String RECOVERY_CANDIDATES = """
            SELECT id FROM jobs
            WHERE status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until < now()
            ORDER BY id""";

    @Autowired
    private JobLifecycleService lifecycle;
    @Autowired
    private JobRunner runner;
    @Autowired
    private JobProcessorProperties properties;
    @Autowired
    private TestRows rows;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecoveryManager recoveryManager;

    private WorkerPool pool;
    private JobDispatcher dispatcher;
    private WorkerIdentity identity;

    @BeforeEach
    void setUp() {
        rows.deleteAllJobs();
        JobProcessorProperties p = properties;
        JobProcessorProperties instance = new JobProcessorProperties(
                new Worker(2, p.worker().cancellationCheckInterval(), "shutdown-scenario"),
                new Dispatcher(true, Duration.ofMillis(50)),
                p.lease(), p.recovery(), p.retry(), p.simulation(),
                new Shutdown(Duration.ofMillis(300)),
                p.idempotency(), p.api(), p.security(), p.metrics());
        identity = new WorkerIdentity(instance);
        pool = new WorkerPool(instance);
        dispatcher = new JobDispatcher(lifecycle, pool, runner, identity, instance);
    }

    @AfterEach
    void stopInstance() {
        dispatcher.stop();
        pool.stop();
    }

    @Test
    void jobsInterruptedByShutdownAreRecoveredAndCompletedByAnotherInstance() {
        UUID owner = rows.insertUser();
        // Both run for the 2s test maximum; the 300 ms drain timeout guarantees interruption.
        UUID first = rows.job(owner).set("payload", "{\"simulation\":{\"durationMs\":2000}}")
                .set("created_at", TestRows.now().minusSeconds(3)).insert();
        UUID second = rows.job(owner).set("payload", "{\"simulation\":{\"durationMs\":2000}}")
                .set("created_at", TestRows.now().minusSeconds(2)).insert();
        UUID waiting = rows.job(owner).set("payload", "{\"simulation\":{\"durationMs\":2000}}")
                .set("created_at", TestRows.now().minusSeconds(1)).insert();

        pool.start();
        dispatcher.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> runningJobsOf(identity.workerId()) == 2);

        // Shutdown in the production order: stop claiming, then drain (times out) and interrupt.
        dispatcher.stop();
        long stopStarted = System.nanoTime();
        pool.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - stopStarted)).isLessThan(Duration.ofSeconds(2));

        // Interrupted workers recorded nothing: still RUNNING, still owned, attempt still open.
        for (UUID id : List.of(first, second)) {
            Map<String, Object> job = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
            assertThat(job).containsEntry("status", "RUNNING")
                    .containsEntry("worker_id", identity.workerId())
                    .containsEntry("fencing_token", 1L)
                    .containsEntry("retry_count", 0);
            assertThat(job.get("lease_until")).isNotNull();
            assertThat(job.get("finished_at")).isNull();
            assertThat(job.get("completed_at")).isNull();
            assertThat(job.get("error_message")).isNull();

            Map<String, Object> attempt = jdbc.queryForMap("SELECT * FROM job_attempts WHERE job_id = ?", id);
            assertThat(attempt).containsEntry("attempt_number", 1).containsEntry("status", "RUNNING");
            assertThat(attempt.get("finished_at")).isNull();
        }
        // The stopped dispatcher claimed nothing more.
        assertThat(jdbc.queryForMap("SELECT status, fencing_token FROM jobs WHERE id = ?", waiting))
                .containsEntry("status", "PENDING").containsEntry("fencing_token", 0L);

        // With no heartbeat from the stopped instance, the leases (2s in tests) expire and the
        // jobs become exactly the input of the Recovery Manager's expired-lease query.
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForList(RECOVERY_CANDIDATES, UUID.class).size() == 2);
        assertThat(jdbc.queryForList(RECOVERY_CANDIDATES, UUID.class)).containsExactlyInAnyOrder(first, second);

        // Recovery (on any instance) takes them over: attempt ABANDONED, job back to PENDING.
        List<RecoveredJob> recovered = recoveryManager.recoverExpiredLeases();
        assertThat(recovered).extracting(RecoveredJob::jobId).containsExactlyInAnyOrder(first, second);
        assertThat(recovered).allSatisfy(r -> {
            assertThat(r.outcome()).isEqualTo(RecoveredJob.Outcome.REQUEUED);
            assertThat(r.previousWorkerId()).isEqualTo(identity.workerId());
        });

        // A second instance processes all three jobs; the recovered ones as a new generation.
        String stoppedWorker = identity.workerId();
        startSecondInstance();
        await().atMost(Duration.ofSeconds(20)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'", Integer.class) == 3);

        for (UUID id : List.of(first, second)) {
            assertThat(jdbc.queryForMap("SELECT retry_count, fencing_token FROM jobs WHERE id = ?", id))
                    .containsEntry("retry_count", 1).containsEntry("fencing_token", 2L);
            List<Map<String, Object>> attempts =
                    jdbc.queryForList("SELECT * FROM job_attempts WHERE job_id = ? ORDER BY attempt_number", id);
            assertThat(attempts).extracting(a -> a.get("status")).containsExactly("ABANDONED", "SUCCEEDED");
            assertThat(attempts.get(0)).containsEntry("worker_id", stoppedWorker);
            assertThat(attempts.get(1)).containsEntry("worker_id", identity.workerId());
        }
        assertThat(jdbc.queryForMap("SELECT retry_count, fencing_token FROM jobs WHERE id = ?", waiting))
                .containsEntry("retry_count", 0).containsEntry("fencing_token", 1L);
    }

    private void startSecondInstance() {
        JobProcessorProperties p = properties;
        JobProcessorProperties second = new JobProcessorProperties(
                new Worker(3, p.worker().cancellationCheckInterval(), "second-instance"),
                new Dispatcher(true, Duration.ofMillis(50)),
                p.lease(), p.recovery(), p.retry(), p.simulation(),
                p.shutdown(), p.idempotency(), p.api(), p.security(), p.metrics());
        identity = new WorkerIdentity(second);
        pool = new WorkerPool(second);
        dispatcher = new JobDispatcher(lifecycle, pool, runner, identity, second);
        pool.start();
        dispatcher.start();
    }

    private int runningJobsOf(String workerId) {
        return jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'RUNNING' AND worker_id = ?",
                Integer.class, workerId);
    }
}
