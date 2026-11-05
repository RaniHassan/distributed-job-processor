package com.example.jobprocessor.api;

import static com.example.jobprocessor.TestRows.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.job.idempotency.IdempotencyKeyCleanup;
import com.example.jobprocessor.user.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Idempotent job creation (README §15.4, §16.1), including concurrent requests over real HTTP. */
@ApiIntegrationTest
class IdempotencyIT {

    private static final String JOBS = "/api/v1/jobs";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private TestUsers users;
    @Autowired
    private TestRows rows;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private IdempotencyKeyCleanup cleanup;

    private TestUser alice;
    private String aliceAuth;

    @BeforeEach
    void createUser() {
        alice = users.create(Role.USER);
        aliceAuth = users.bearer(alice);
    }

    private ResultActions create(String auth, String key, String body) throws Exception {
        return mvc.perform(post(JOBS).header(HttpHeaders.AUTHORIZATION, auth).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String idOf(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private long jobCount(UUID ownerId) {
        return jdbc.queryForObject("SELECT count(*) FROM jobs WHERE owner_id = ?", Long.class, ownerId);
    }

    @Test
    void retriedRequestReturnsTheOriginalJob() throws Exception {
        String body = "{\"payload\":{\"report\":\"daily\"}}";
        ResultActions first = create(aliceAuth, "retry-1", body).andExpect(status().isAccepted());
        String location = first.andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        ResultActions second = create(aliceAuth, "retry-1", body)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string(HttpHeaders.LOCATION, location));

        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(jobCount(alice.id())).isEqualTo(1);
    }

    @Test
    void replayReflectsTheJobsCurrentState() throws Exception {
        String body = "{\"payload\":{}}";
        String id = idOf(create(aliceAuth, "state-1", body));
        jdbc.update("UPDATE jobs SET status = 'CANCELLED', finished_at = now() WHERE id = ?", UUID.fromString(id));

        create(aliceAuth, "state-1", body).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void fieldOrderDoesNotCauseAFalseConflict() throws Exception {
        String first = idOf(create(aliceAuth, "order-1", "{\"payload\":{\"a\":1,\"b\":{\"x\":true,\"y\":[1,2]}}}"));
        ResultActions second = create(aliceAuth, "order-1", "{\"payload\":{\"b\":{\"y\":[1,2],\"x\":true},\"a\":1}}")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        assertThat(idOf(second)).isEqualTo(first);
    }

    @Test
    void sameKeyWithDifferentRequestIsAConflict() throws Exception {
        create(aliceAuth, "conflict-1", "{\"payload\":{\"a\":1}}").andExpect(status().isAccepted());

        create(aliceAuth, "conflict-1", "{\"payload\":{\"a\":2}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Idempotency key was already used with a different request"));
        assertThat(jobCount(alice.id())).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        TestUser bob = users.create(Role.USER);
        String body = "{\"payload\":{}}";

        String aliceJob = idOf(create(aliceAuth, "shared-key", body).andExpect(status().isAccepted()));
        String bobJob = idOf(create(users.bearer(bob), "shared-key", body)
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotency-Replayed")));

        assertThat(bobJob).isNotEqualTo(aliceJob);
    }

    @Test
    void expiredKeyCanBeReusedBeforeCleanupRemovesIt() throws Exception {
        String oldJob = idOf(create(aliceAuth, "expiring", "{\"payload\":{\"v\":1}}"));
        expireKey("expiring");

        // A different request with the same key is now a new job, not a conflict.
        String newJob = idOf(create(aliceAuth, "expiring", "{\"payload\":{\"v\":2}}")
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotency-Replayed")));

        assertThat(newJob).isNotEqualTo(oldJob);
        Map<String, Object> key = jdbc.queryForMap(
                "SELECT job_id, expires_at > now() AS live FROM idempotency_keys WHERE owner_id = ? AND idempotency_key = 'expiring'",
                alice.id());
        assertThat(key).containsEntry("job_id", UUID.fromString(newJob)).containsEntry("live", true);
        assertThat(jobCount(alice.id())).isEqualTo(2);
        // And the rebound key now replays the new job.
        assertThat(idOf(create(aliceAuth, "expiring", "{\"payload\":{\"v\":2}}"))).isEqualTo(newJob);
    }

    @Test
    void expiredKeyWithSameRequestCreatesANewJob() throws Exception {
        String body = "{\"payload\":{\"v\":1}}";
        String oldJob = idOf(create(aliceAuth, "expiring-same", body));
        expireKey("expiring-same");

        assertThat(idOf(create(aliceAuth, "expiring-same", body).andExpect(header().doesNotExist("Idempotency-Replayed"))))
                .isNotEqualTo(oldJob);
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOneJob() throws Exception {
        List<ResponseEntity<String>> responses = sendConcurrently(10, i -> "{\"payload\":{\"n\":1}}", "concurrent-same");

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(202));
        Set<String> ids = responses.stream().map(this::bodyId).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        long originals = responses.stream().filter(r -> r.getHeaders().get("Idempotency-Replayed") == null).count();
        assertThat(originals).isEqualTo(1);
        assertThat(jobCount(alice.id())).isEqualTo(1);
    }

    @Test
    void concurrentConflictingRequestsCreateOneJobAndRejectTheRest() throws Exception {
        List<ResponseEntity<String>> responses = sendConcurrently(8, i -> "{\"payload\":{\"n\":" + i + "}}", "concurrent-diff");

        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 202)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 409)).hasSize(7);
        assertThat(jobCount(alice.id())).isEqualTo(1);
    }

    @Test
    void cleanupDeletesOnlyExpiredKeys() throws Exception {
        UUID jobId = rows.job(alice.id()).insert();
        rows.idempotencyKey(alice.id(), jobId, "old-1")
                .set("created_at", now().minusDays(3)).set("expires_at", now().minusDays(2)).insert();
        rows.idempotencyKey(alice.id(), jobId, "old-2")
                .set("created_at", now().minusDays(2)).set("expires_at", now().minusSeconds(1)).insert();
        rows.idempotencyKey(alice.id(), jobId, "live").insert();

        assertThat(cleanup.purgeExpired()).isGreaterThanOrEqualTo(2);

        assertThat(jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys WHERE owner_id = ?", String.class, alice.id()))
                .containsExactly("live");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE id = ?", Long.class, jobId))
                .as("jobs are never deleted with their keys").isEqualTo(1);
    }

    private void expireKey(String key) {
        jdbc.update("""
                UPDATE idempotency_keys SET created_at = now() - interval '2 days', expires_at = now() - interval '1 second'
                WHERE owner_id = ? AND idempotency_key = ?""", alice.id(), key);
    }

    private String bodyId(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody()).get("id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fires {@code count} requests at once, released together by a latch. */
    private List<ResponseEntity<String>> sendConcurrently(int count, java.util.function.IntFunction<String> body, String key)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, aliceAuth);
        headers.set("Idempotency-Key", key);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                HttpEntity<String> request = new HttpEntity<>(body.apply(i), headers);
                Callable<ResponseEntity<String>> call = () -> {
                    start.await();
                    return http.postForEntity(JOBS, request, String.class);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
