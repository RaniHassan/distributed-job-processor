package com.example.jobprocessor.api;

import static com.example.jobprocessor.TestRows.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.user.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@ApiIntegrationTest
class JobApiIT {

    private static final String JOBS = "/api/v1/jobs";
    private static final String BODY = "{\"payload\":{\"someParameter\":\"someValue\"}}";

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

    private TestUser alice;
    private String aliceAuth;
    private TestUser bob;
    private String bobAuth;

    @BeforeEach
    void createUsers() {
        alice = users.create(Role.USER);
        aliceAuth = users.bearer(alice);
        bob = users.create(Role.USER);
        bobAuth = users.bearer(bob);
    }

    private ResultActions create(String auth, String key, String body) throws Exception {
        var request = post(JOBS).header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private UUID createJob(String auth) throws Exception {
        String response = create(auth, UUID.randomUUID().toString(), BODY)
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private long jobCount(UUID ownerId) {
        return jdbc.queryForObject("SELECT count(*) FROM jobs WHERE owner_id = ?", Long.class, ownerId);
    }

    @Nested
    class Create {

        @Test
        void acceptsJobAndPersistsItAsPending() throws Exception {
            String response = create(aliceAuth, "key-1", BODY)
                    .andExpect(status().isAccepted())
                    .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/jobs/[0-9a-f-]{36}")))
                    .andExpect(header().doesNotExist("Idempotency-Replayed"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            UUID id = UUID.fromString(json.readTree(response).get("id").asText());

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT owner_id, status, max_retries, retry_count, payload::text AS payload FROM jobs WHERE id = ?", id);
            assertThat(row).containsEntry("owner_id", alice.id()).containsEntry("status", "PENDING")
                    .containsEntry("max_retries", 3).containsEntry("retry_count", 0);
            assertThat(json.readTree((String) row.get("payload")))
                    .isEqualTo(json.readTree("{\"someParameter\":\"someValue\"}"));
            assertThat(jdbc.queryForObject(
                    "SELECT job_id FROM idempotency_keys WHERE owner_id = ? AND idempotency_key = 'key-1'", UUID.class, alice.id()))
                    .isEqualTo(id);
        }

        @Test
        void locationPointsToTheJob() throws Exception {
            String location = create(aliceAuth, "key-1", BODY).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

            mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, aliceAuth)).andExpect(status().isOk());
        }

        @Test
        void requiresIdempotencyKey() throws Exception {
            create(aliceAuth, null, BODY)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_MISSING"));
            assertThat(jobCount(alice.id())).isZero();
        }

        @Test
        void rejectsBlankOrOverlongKeys() throws Exception {
            create(aliceAuth, "   ", BODY).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_INVALID"));
            create(aliceAuth, "k".repeat(256), BODY).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_INVALID"));
            create(aliceAuth, "k".repeat(255), BODY).andExpect(status().isAccepted());
            assertThat(jobCount(alice.id())).isEqualTo(1);
        }

        @Test
        void rejectsMissingPayload() throws Exception {
            create(aliceAuth, "k", "{}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("payload"));
        }

        @Test
        void rejectsStructurallyInvalidBodies() throws Exception {
            String nested = "{\"a\":".repeat(40) + "1" + "}".repeat(40);
            for (String body : new String[] {
                "{\"payload\":[1,2]}",                              // payload must be an object
                "{\"payload\":\"text\"}",
                "{\"payload\":{},\"extra\":true}",                  // unknown top-level field
                "{\"payload\":{\"a\":1,\"a\":2}}",                  // duplicate keys
                "{\"payload\":{\"a\":1}",                           // malformed JSON
                "{\"payload\":" + nested + "}",                     // nesting deeper than the limit
                ""}) {
                create(aliceAuth, UUID.randomUUID().toString(), body)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
            }
            assertThat(jobCount(alice.id())).isZero();
        }

        @Test
        void rejectsOversizedBody() throws Exception {
            String body = "{\"payload\":{\"data\":\"" + "x".repeat(70 * 1024) + "\"}}";

            create(aliceAuth, "k", body)
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));
        }

        @Test
        void rejectsNonJsonContent() throws Exception {
            mvc.perform(post(JOBS).header(HttpHeaders.AUTHORIZATION, aliceAuth).header("Idempotency-Key", "k")
                            .contentType(MediaType.TEXT_PLAIN).content("hello"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
        }

        @Test
        void acceptsValidSimulationControls() throws Exception {
            create(aliceAuth, "k", "{\"payload\":{\"simulation\":{\"durationMs\":1000,\"failureMode\":\"TRANSIENT_ONCE\",\"failAfterMs\":100}}}")
                    .andExpect(status().isAccepted());
        }

        @Test
        void rejectsInvalidSimulationControls() throws Exception {
            create(aliceAuth, "k1", "{\"payload\":{\"simulation\":{\"durationMs\":10}}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PAYLOAD"));
            create(aliceAuth, "k2", "{\"payload\":{\"simulation\":{\"failureMode\":\"MELTDOWN\"}}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PAYLOAD"));
            assertThat(jobCount(alice.id())).isZero();
        }
    }

    @Nested
    class Read {

        @Test
        void returnsOwnJobWithPublicFieldsOnly() throws Exception {
            UUID id = createJob(aliceAuth);

            String body = mvc.perform(get(JOBS + "/" + id).header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.retryCount").value(0))
                    .andExpect(jsonPath("$.maxRetries").value(3))
                    .andExpect(jsonPath("$.startedAt").value(nullValue()))
                    .andExpect(jsonPath("$.completedAt").value(nullValue()))
                    .andExpect(jsonPath("$.finishedAt").value(nullValue()))
                    .andExpect(jsonPath("$.error").value(nullValue()))
                    .andReturn().getResponse().getContentAsString();

            JsonNode node = json.readTree(body);
            for (String hidden : new String[] {"workerId", "leaseUntil", "fencingToken", "ownerId", "payload", "heartbeatAt"}) {
                assertThat(node.has(hidden)).as(hidden).isFalse();
            }
        }

        @Test
        void otherUsersJobLooksExactlyLikeAMissingJob() throws Exception {
            UUID id = createJob(aliceAuth);

            JsonNode foreign = json.readTree(mvc.perform(get(JOBS + "/" + id).header(HttpHeaders.AUTHORIZATION, bobAuth))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
            JsonNode missing = json.readTree(mvc.perform(get(JOBS + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bobAuth))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());

            assertThat(foreign.get("errorCode")).isEqualTo(missing.get("errorCode"));
            assertThat(foreign.get("detail")).isEqualTo(missing.get("detail"));
            assertThat(foreign.get("errorCode").asText()).isEqualTo("JOB_NOT_FOUND");
        }

        @Test
        void adminHasNoOwnershipBypass() throws Exception {
            UUID id = createJob(aliceAuth);
            String adminAuth = users.bearer(users.create(Role.ADMIN));

            mvc.perform(get(JOBS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminAuth)).andExpect(status().isNotFound());
            mvc.perform(delete(JOBS + "/" + id).header(HttpHeaders.AUTHORIZATION, adminAuth)).andExpect(status().isNotFound());
            mvc.perform(get(JOBS + "/" + id + "/attempts").header(HttpHeaders.AUTHORIZATION, adminAuth))
                    .andExpect(status().isNotFound());
        }

        @Test
        void invalidIdIs400() throws Exception {
            mvc.perform(get(JOBS + "/not-a-uuid").header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }

        @Test
        void listsOnlyOwnJobsNewestFirst() throws Exception {
            OffsetDateTime base = OffsetDateTime.parse("2026-01-01T00:00:00Z");
            UUID oldest = rows.job(alice.id()).set("created_at", base).insert();
            UUID middle = rows.job(alice.id()).set("created_at", base.plusMinutes(1)).insert();
            UUID newest = rows.job(alice.id()).set("created_at", base.plusMinutes(2)).insert();
            rows.job(bob.id()).insert();

            mvc.perform(get(JOBS + "?page=0&size=2").header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].id").value(newest.toString()))
                    .andExpect(jsonPath("$.content[1].id").value(middle.toString()))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2));
            mvc.perform(get(JOBS + "?page=1&size=2&sort=createdAt,desc").header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(jsonPath("$.content[0].id").value(oldest.toString()));
        }

        @Test
        void listUsesDefaultAndMaximumPageSizes() throws Exception {
            mvc.perform(get(JOBS).header(HttpHeaders.AUTHORIZATION, aliceAuth)).andExpect(jsonPath("$.size").value(20));
            mvc.perform(get(JOBS + "?size=500").header(HttpHeaders.AUTHORIZATION, aliceAuth)).andExpect(jsonPath("$.size").value(100));
        }

        @Test
        void listRejectsUnsupportedSort() throws Exception {
            mvc.perform(get(JOBS + "?sort=payload,asc").header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_SORT"));
        }

        @Test
        void returnsAttemptHistoryWithoutInternalFields() throws Exception {
            UUID id = rows.job(alice.id()).running().set("fencing_token", 2L).set("retry_count", 1).insert();
            rows.attempt(id, 1).set("status", "FAILED").set("finished_at", now()).set("error_message", "timeout").insert();
            rows.attempt(id, 2).insert();

            String body = mvc.perform(get(JOBS + "/" + id + "/attempts").header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].attemptNumber").value(1))
                    .andExpect(jsonPath("$[0].status").value("FAILED"))
                    .andExpect(jsonPath("$[0].error").value("timeout"))
                    .andExpect(jsonPath("$[1].status").value("RUNNING"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("worker-a", "fencingToken", "workerId");

            mvc.perform(get(JOBS + "/" + id + "/attempts").header(HttpHeaders.AUTHORIZATION, bobAuth))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Cancel {

        private ResultActions cancel(UUID id, String auth) throws Exception {
            return mvc.perform(delete(JOBS + "/" + id).header(HttpHeaders.AUTHORIZATION, auth));
        }

        private Map<String, Object> row(UUID id) {
            return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
        }

        @Test
        void pendingJobIsCancelledImmediately() throws Exception {
            UUID id = createJob(aliceAuth);

            cancel(id, aliceAuth)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                    .andExpect(jsonPath("$.completedAt").value(nullValue()));
            Map<String, Object> row = row(id);
            assertThat(row.get("cancel_requested_at")).isNotNull();
            assertThat(row.get("finished_at")).isNotNull();
            assertThat(row.get("completed_at")).isNull();
        }

        @Test
        void runningJobMovesToCancelRequestedAndKeepsItsOwner() throws Exception {
            UUID id = rows.job(alice.id()).running().insert();

            cancel(id, aliceAuth).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));

            Map<String, Object> row = row(id);
            assertThat(row).containsEntry("status", "CANCEL_REQUESTED").containsEntry("worker_id", "worker-a")
                    .containsEntry("fencing_token", 1L);
            assertThat(row.get("lease_until")).isNotNull();
            assertThat(row.get("cancel_requested_at")).isNotNull();
            assertThat(row.get("finished_at")).isNull();
        }

        @Test
        void repeatedRequestsAreIdempotent() throws Exception {
            UUID running = rows.job(alice.id()).running().insert();
            cancel(running, aliceAuth).andExpect(status().isAccepted());
            Object requestedAt = row(running).get("cancel_requested_at");
            cancel(running, aliceAuth).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
            assertThat(row(running).get("cancel_requested_at")).isEqualTo(requestedAt);

            UUID pending = createJob(aliceAuth);
            cancel(pending, aliceAuth).andExpect(status().isOk());
            Object finishedAt = row(pending).get("finished_at");
            cancel(pending, aliceAuth).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            assertThat(row(pending).get("finished_at")).isEqualTo(finishedAt);
        }

        @Test
        void finishedJobsAreConflicts() throws Exception {
            UUID completed = rows.job(alice.id()).completed().insert();
            UUID failed = rows.job(alice.id()).failed().insert();

            cancel(completed, aliceAuth).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("JOB_NOT_CANCELLABLE"));
            cancel(failed, aliceAuth).andExpect(status().isConflict());
            assertThat(row(completed)).containsEntry("status", "COMPLETED");
            assertThat(row(failed)).containsEntry("status", "FAILED");
        }

        @Test
        void otherUsersCannotCancel() throws Exception {
            UUID id = createJob(aliceAuth);

            cancel(id, bobAuth).andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("JOB_NOT_FOUND"));
            assertThat(row(id)).containsEntry("status", "PENDING");
            cancel(UUID.randomUUID(), aliceAuth).andExpect(status().isNotFound());
        }
    }

    @Nested
    class ErrorFormat {

        @Test
        void traceIdIsEchoedInHeaderAndBody() throws Exception {
            mvc.perform(get(JOBS + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, aliceAuth)
                            .header("X-Request-Id", "trace-abc-123"))
                    .andExpect(header().string("X-Request-Id", "trace-abc-123"))
                    .andExpect(jsonPath("$.traceId").value("trace-abc-123"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.title").value("Not Found"));
        }

        @Test
        void generatedTraceIdWhenNoneSupplied() throws Exception {
            String traceId = mvc.perform(get(JOBS + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andReturn().getResponse().getHeader("X-Request-Id");

            assertThat(traceId).matches("[0-9a-f-]{36}");
        }

        @Test
        void unsupportedMethodAndUnknownPathUseProblemFormat() throws Exception {
            mvc.perform(put(JOBS + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
            mvc.perform(get("/api/v1/nothing-here").header(HttpHeaders.AUTHORIZATION, aliceAuth))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
        }

        @Test
        void errorsNeverContainStackTraces() throws Exception {
            String body = create(aliceAuth, "k", "{\"payload\":").andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("Exception", "at com.", "jackson");
            assertThat(body).contains("MALFORMED_REQUEST");
        }
    }

    @Test
    void locationUsesTheJobsPath() throws Exception {
        create(aliceAuth, "loc", BODY).andExpect(header().string(HttpHeaders.LOCATION, endsWith(
                jdbc.queryForObject("SELECT job_id FROM idempotency_keys WHERE owner_id = ? AND idempotency_key = 'loc'",
                        UUID.class, alice.id()).toString())));
    }
}
