package com.example.jobprocessor.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobprocessor.ProcessingIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import com.example.jobprocessor.worker.pool.WorkerPool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** End-to-end asynchronous processing with the real Dispatcher, worker pool and executor. */
@ProcessingIntegrationTest
class JobProcessingIT {

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
    private ObjectMapper json;
    @Autowired
    private WorkerPool pool;
    @Autowired
    private WorkerIdentity identity;

    private TestUser alice;
    private String aliceAuth;

    @BeforeEach
    void setUp() {
        awaitIdlePool();
        rows.deleteAllJobs();
        alice = users.create(Role.USER);
        aliceAuth = users.bearer(alice);
    }

    @AfterEach
    void drain() {
        awaitIdlePool();
    }

    private void awaitIdlePool() {
        await().atMost(TIMEOUT).until(() -> pool.activeCount() == 0);
    }

    private String jobStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id);
    }

    private UUID insertJob(String simulation) {
        return rows.job(alice.id()).set("payload", "{\"simulation\":" + simulation + "}").insert();
    }

    @Test
    void acceptedJobIsProcessedAsynchronously() throws Exception {
        long started = System.nanoTime();
        MvcResult result = mvc.perform(post("/api/v1/jobs").header(HttpHeaders.AUTHORIZATION, aliceAuth)
                        .header("Idempotency-Key", "async-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"simulation\":{\"durationMs\":1500}}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        Duration responseTime = Duration.ofNanos(System.nanoTime() - started);
        UUID id = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());

        assertThat(responseTime).as("the API does not wait for execution").isLessThan(Duration.ofMillis(1500));
        await().atMost(TIMEOUT).until(() -> "RUNNING".equals(jobStatus(id)));
        await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(jobStatus(id)));

        mvc.perform(get("/api/v1/jobs/" + id).header(HttpHeaders.AUTHORIZATION, aliceAuth))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
        assertThat(row).containsEntry("fencing_token", 1L);
        assertThat(row.get("worker_id")).isNull();
        Map<String, Object> attempt = jdbc.queryForMap("SELECT * FROM job_attempts WHERE job_id = ?", id);
        assertThat(attempt).containsEntry("status", "SUCCEEDED").containsEntry("worker_id", identity.workerId());
        assertThat(identity.workerId()).startsWith("test-");
    }

    @Test
    void concurrencyIsBoundedByTheWorkerPool() {
        int jobCount = 12;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            ids.add(insertJob("{\"durationMs\":1200}"));
        }
        AtomicInteger maxRunning = new AtomicInteger();
        AtomicInteger maxActiveThreads = new AtomicInteger();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(25)).until(() -> {
            int running = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'RUNNING'", Integer.class);
            maxRunning.accumulateAndGet(running, Math::max);
            maxActiveThreads.accumulateAndGet(pool.activeCount(), Math::max);
            return jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'", Integer.class) == jobCount;
        });

        assertThat(maxRunning.get()).as("never more RUNNING jobs than threads").isLessThanOrEqualTo(5);
        assertThat(maxActiveThreads.get()).isLessThanOrEqualTo(pool.capacity());
        assertThat(maxRunning.get()).as("jobs ran in parallel").isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_attempts WHERE status = 'SUCCEEDED'", Integer.class))
                .isEqualTo(jobCount);
    }

    @Test
    void permanentFailureFailsTheJob() {
        UUID id = insertJob("{\"durationMs\":500,\"failureMode\":\"PERMANENT\",\"failAfterMs\":100}");

        await().atMost(TIMEOUT).until(() -> "FAILED".equals(jobStatus(id)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
        assertThat(row).containsEntry("error_message", "[PERMANENT] Simulated permanent failure");
        assertThat(row.get("completed_at")).isNull();
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("worker_id")).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM job_attempts WHERE job_id = ?", id))
                .as("permanent failures are not retried").singleElement()
                .satisfies(a -> assertThat(a).containsEntry("status", "FAILED")
                        .containsEntry("error_message", "[PERMANENT] Simulated permanent failure"));
        assertThat(row).containsEntry("retry_count", 0);
    }

    /** README §12: a transient failure is retried after a backoff, and the retry can succeed. */
    @Test
    void transientFailureIsRetriedAndThenSucceeds() {
        UUID id = insertJob("{\"durationMs\":500,\"failureMode\":\"TRANSIENT_ONCE\"}");

        await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(jobStatus(id)));

        assertThat(jdbc.queryForMap("SELECT retry_count, fencing_token, error_message FROM jobs WHERE id = ?", id))
                .containsEntry("retry_count", 1).containsEntry("fencing_token", 2L)
                .containsEntry("error_message", "[TRANSIENT] Simulated transient failure");
        assertThat(jdbc.queryForList("SELECT status FROM job_attempts WHERE job_id = ? ORDER BY attempt_number", String.class, id))
                .containsExactly("FAILED", "SUCCEEDED");
    }

    /**
     * Transient and unexpected failures use the same bounded budget: the initial attempt plus
     * 3 retries (backoff 200/400/800 ms in tests), then FAILED. Never retried indefinitely.
     */
    @Test
    void persistentFailuresStopAfterTheRetryBudget() {
        UUID transientJob = insertJob("{\"durationMs\":500,\"failureMode\":\"TRANSIENT_ALWAYS\"}");
        UUID unexpectedJob = insertJob("{\"durationMs\":500,\"failureMode\":\"UNEXPECTED_ALWAYS\"}");

        await().atMost(Duration.ofSeconds(30))
                .until(() -> "FAILED".equals(jobStatus(transientJob)) && "FAILED".equals(jobStatus(unexpectedJob)));

        for (UUID id : List.of(transientJob, unexpectedJob)) {
            assertThat(jdbc.queryForObject("SELECT retry_count FROM jobs WHERE id = ?", Integer.class, id)).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT status FROM job_attempts WHERE job_id = ? ORDER BY attempt_number",
                    String.class, id)).containsExactly("FAILED", "FAILED", "FAILED", "FAILED");
        }
        assertThat(jdbc.queryForObject("SELECT error_message FROM jobs WHERE id = ?", String.class, transientJob))
                .isEqualTo("[TRANSIENT] Simulated transient failure");
        assertThat(jdbc.queryForObject("SELECT error_message FROM jobs WHERE id = ?", String.class, unexpectedJob))
                .isEqualTo("[UNEXPECTED] Unexpected processing error");
        // Each retry waited for its backoff: 200 + 400 + 800 ms between the four attempts at least.
        assertThat(jdbc.queryForObject("""
                SELECT max(started_at) - min(started_at) >= interval '1.4 seconds'
                FROM job_attempts WHERE job_id = ?""", Boolean.class, transientJob)).isTrue();
    }

    @Test
    void jobsAreNotClaimedBeforeTheyAreEligible() throws Exception {
        UUID later = rows.job(alice.id()).set("payload", "{\"simulation\":{\"durationMs\":500}}")
                .set("next_retry_at", TestRows.now().plusSeconds(2)).insert();

        Thread.sleep(1000);
        assertThat(jobStatus(later)).isEqualTo("PENDING");

        await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(jobStatus(later)));
    }
}
