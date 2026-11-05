package com.example.jobprocessor.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.job.domain.JobStatus;
import com.example.jobprocessor.job.dto.CreateJobRequest;
import com.example.jobprocessor.security.authorization.Permission;
import com.example.jobprocessor.user.domain.Role;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Authorization is enforced by the service itself, not just the HTTP layer: these calls bypass
 * controllers and filters entirely.
 */
@ApiIntegrationTest
class JobManagementServiceAuthorizationIT {

    @Autowired
    private JobManagementService service;
    @Autowired
    private TestUsers users;
    @Autowired
    private TestRows rows;

    private TestUser alice;
    private UUID aliceJob;

    @BeforeEach
    void setUp() {
        alice = users.create(Role.USER);
        aliceJob = rows.job(alice.id()).insert();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void everyOperationRequiresAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.getJob(aliceJob)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.listJobs(Pageable.ofSize(10))).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.getAttempts(aliceJob)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.cancelJob(aliceJob)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.createJob("k", new CreateJobRequest(Map.of())))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void eachOperationRequiresItsOwnPermission() {
        authenticateAs(alice, Permission.JOB_READ);

        assertThat(service.getJob(aliceJob).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThatThrownBy(() -> service.cancelJob(aliceJob)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.createJob("k", new CreateJobRequest(Map.of()))).isInstanceOf(AccessDeniedException.class);

        authenticateAs(alice, Permission.JOB_CANCEL);
        assertThatThrownBy(() -> service.getJob(aliceJob)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.cancelJob(aliceJob).outcome()).isEqualTo(CancelOutcome.CANCELLED);
    }

    @Test
    void permissionDoesNotGrantAccessToOtherUsersJobs() {
        TestUser mallory = users.create(Role.USER);
        authenticateAs(mallory, Permission.values());

        assertThatThrownBy(() -> service.getJob(aliceJob)).isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> service.cancelJob(aliceJob)).isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> service.getAttempts(aliceJob)).isInstanceOf(JobNotFoundException.class);
        assertThat(service.listJobs(Pageable.ofSize(10)).getContent()).isEmpty();
    }

    private static void authenticateAs(TestUser user, Permission... permissions) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .subject(user.id().toString()).claim("username", user.username()).claim("role", user.user().getRole().name())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        List<GrantedAuthority> authorities = Arrays.stream(permissions)
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name())).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities, user.id().toString()));
    }
}
