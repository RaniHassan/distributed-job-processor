package com.example.jobprocessor.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.user.domain.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ApiIntegrationTest
class AuthApiIT {

    private static final String JOBS = "/api/v1/jobs";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestUsers users;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private JobProcessorProperties properties;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;

    private TestUser alice;

    @BeforeEach
    void createUser() {
        alice = users.create(Role.USER);
    }

    // ---- login --------------------------------------------------------------------------------

    @Test
    void loginIssuesUsableBearerToken() throws Exception {
        MvcResult result = login(alice.username(), alice.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn();
        String token = json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(get(JOBS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
        JsonNode wrongPassword = problem(login(alice.username(), "wrong-password").andExpect(status().isUnauthorized()));
        JsonNode unknownUser = problem(login("nobody-" + UUID.randomUUID(), "whatever").andExpect(status().isUnauthorized()));

        for (JsonNode body : List.of(wrongPassword, unknownUser)) {
            assertThat(body.get("errorCode").asText()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(body.get("detail").asText()).isEqualTo("Invalid username or password");
        }
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        jdbc.update("UPDATE users SET enabled = false WHERE id = ?", alice.id());

        login(alice.username(), alice.password())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginValidatesRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void responsesNeverContainThePassword() throws Exception {
        String body = login(alice.username(), alice.password()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(alice.password());
    }

    // ---- token validation ---------------------------------------------------------------------

    @Test
    void missingTokenIs401WithChallengeAndProblemBody() throws Exception {
        mvc.perform(get(JOBS).header("X-Request-Id", "req-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("req-401"));
    }

    @Test
    void malformedTokenIs401() throws Exception {
        assertRejected("not-a-jwt");
    }

    @Test
    void expiredTokenIs401() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        assertRejected(sign(jwtEncoder, b -> b.issuedAt(past.minusSeconds(900)).expiresAt(past)));
    }

    @Test
    void tokenFromAnotherIssuerIs401() throws Exception {
        assertRejected(sign(jwtEncoder, b -> b.issuer("https://evil.example")));
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() throws Exception {
        byte[] otherKey = "a-completely-different-signing-key-0123456789".getBytes(StandardCharsets.UTF_8);
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));

        assertRejected(sign(forger, b -> {}));
    }

    @Test
    void tokenWithUnknownRoleIs401() throws Exception {
        assertRejected(sign(jwtEncoder, b -> b.claim("role", "SUPERUSER")));
    }

    @Test
    void tokenWithoutExpiryIs401() throws Exception {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.security().jwt().issuer()).issuedAt(Instant.now())
                .subject(alice.id().toString()).claim("username", alice.username()).claim("role", "USER").build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertRejected(token);
    }

    @Test
    void validCraftedTokenIsAccepted() throws Exception {
        mvc.perform(get(JOBS).header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(jwtEncoder, b -> {})))
                .andExpect(status().isOk());
    }

    // ---- authorization ------------------------------------------------------------------------

    @Test
    void authenticatedCallerWithoutPermissionIs403() throws Exception {
        mvc.perform(get(JOBS).with(jwt()
                        .jwt(j -> j.subject(alice.id().toString()).claim("username", alice.username()).claim("role", "USER"))
                        .authorities(List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /** D2: operational metrics (JSON and Prometheus) are for ADMIN only; other management endpoints are not exposed. */
    @Test
    void metricsAreAvailableToAdminsOnly() throws Exception {
        String userBearer = users.bearer(alice);
        String adminBearer = users.bearer(users.create(Role.ADMIN));

        for (String endpoint : new String[] {"/actuator/metrics", "/actuator/metrics/jobs.submitted", "/actuator/prometheus"}) {
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, userBearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        }

        String scrape = mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.startsWith("text/plain")))
                .andReturn().getResponse().getContentAsString();
        String labels = "{application=\"distributed-job-processor\",instance=\"test\"}";
        assertThat(scrape)
                .contains("# TYPE jobs_submitted_total counter", "jobs_submitted_total" + labels)
                .contains("# TYPE jobs_pending gauge", "jobs_pending" + labels)
                .contains("worker_pool_active_threads" + labels, "worker_pool_queue_size" + labels)
                .contains("job_queue_wait_duration_seconds_count" + labels);

        mvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").value(org.hamcrest.Matchers.hasItems(
                        "jobs.submitted", "jobs.completed", "jobs.failed", "jobs.cancelled", "jobs.retried",
                        "jobs.pending", "jobs.running", "job.queue.wait.duration",
                        "worker.pool.active.threads", "worker.pool.queue.size", "worker.pool.capacity")));
        mvc.perform(get("/actuator/metrics/jobs.submitted").header(HttpHeaders.AUTHORIZATION, adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'instance')].values[0]").value("test"));

        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, adminBearer)).andExpect(status().isNotFound());
    }

    // ---- helpers ------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("username", username, "password", password));
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode problem(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private void assertRejected(String token) throws Exception {
        mvc.perform(get(JOBS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    /** A token for alice with valid defaults that the customizer can override. */
    private String sign(JwtEncoder encoder, Consumer<JwtClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.security().jwt().issuer())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .subject(alice.id().toString())
                .claim("username", alice.username())
                .claim("role", "USER");
        customizer.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
