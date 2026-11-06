package com.example.jobprocessor.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobprocessor.PausablePostgres;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.worker.execution.ActiveExecutions;
import com.example.jobprocessor.worker.pool.WorkerIdentity;
import com.example.jobprocessor.worker.pool.WorkerPool;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * README §23.4 #3, "Database becomes unavailable", against a real outage: the PostgreSQL container
 * is paused mid-job (connections stay open but nothing answers, like a network partition), then
 * resumed. Short JDBC timeouts make the outage visible within seconds.
 *
 * <p>Own context and database, so pausing it cannot affect any other test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jobprocessor.dispatcher.enabled=true",
        "jobprocessor.recovery.enabled=true",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=2",
        "spring.datasource.hikari.data-source-properties.connectTimeout=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PausablePostgres.class, TestUsers.class, TestRows.class})
class DatabaseOutageIT {

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

    @AfterEach
    void resumeDatabase() {
        PausablePostgres.resume();
    }

    @Test
    void outageIsReportedWorkersSelfFenceAndProcessingResumesAfterwards() throws Exception {
        TestUser alice = users.create(Role.USER);
        String auth = users.bearer(alice); // tokens are validated without the database
        UUID id = rows.job(alice.id()).set("payload", "{\"simulation\":{\"durationMs\":6000}}").insert();
        await().atMost(Duration.ofSeconds(10)).until(() ->
                "RUNNING".equals(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id)));

        // Docker can take a moment to freeze the container; the outage starts once pause() returns.
        PausablePostgres.pause();
        long outageStarted = System.nanoTime();
        // Timed independently of the HTTP checks below, which themselves wait for JDBC timeouts.
        CompletableFuture<Duration> workerStopped = CompletableFuture.supplyAsync(() -> {
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                    .until(() -> pool.activeCount() == 0 && executions.size() == 0);
            return Duration.ofNanos(System.nanoTime() - outageStarted);
        });

        // The API answers with 503 instead of hanging, and no internals leak.
        mvc.perform(get("/api/v1/jobs/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("The service is temporarily unavailable, please retry later"));
        // Readiness takes the instance out of rotation; liveness does not restart it.
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());

        // Heartbeats cannot confirm the 2s lease, so the worker stops itself well before the 6s job
        // would end, and before Recovery may take the job over.
        assertThat(workerStopped.get(15, TimeUnit.SECONDS))
                .as("self-fenced at the local lease deadline, not at job end").isLessThan(Duration.ofMillis(3500));

        PausablePostgres.resume();

        // Everything comes back without a restart: readiness, the API, and the job itself, which
        // Recovery requeues (its lease expired during the outage) and this instance completes.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()));
        await().atMost(Duration.ofSeconds(30)).until(() ->
                "COMPLETED".equals(jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, id)));
        mvc.perform(get("/api/v1/jobs/" + id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.retryCount").value(1));

        List<String> attempts = jdbc.queryForList(
                "SELECT status FROM job_attempts WHERE job_id = ? ORDER BY attempt_number", String.class, id);
        assertThat(attempts).as("the interrupted attempt wrote nothing; the retry succeeded").containsExactly("ABANDONED", "SUCCEEDED");
        assertThat(jdbc.queryForList("SELECT worker_id FROM job_attempts WHERE job_id = ?", String.class, id))
                .containsOnly(identity.workerId());
    }
}
