package com.example.jobprocessor.job.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.TestUsers;
import com.example.jobprocessor.TestUsers.TestUser;
import com.example.jobprocessor.security.authorization.Permission;
import com.example.jobprocessor.user.domain.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Races between a cancellation request and the job's lifecycle, with real concurrent
 * transactions (README §9.2). Both sides lock the job row, so whichever commits first decides,
 * and the other observes it.
 */
@ApiIntegrationTest
class CancellationRaceIT {

    @Autowired
    private JobManagementService jobManagement;
    @Autowired
    private JobLifecycleService lifecycle;
    @Autowired
    private TestUsers users;
    @Autowired
    private TestRows rows;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private TestUser alice;
    private ExecutorService threads;

    @BeforeEach
    void setUp() {
        rows.deleteAllJobs();
        alice = users.create(Role.USER);
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    /** Requests cancellation as alice, from a separate thread. */
    private Future<Object> cancelAsync(UUID jobId, CountDownLatch start) {
        return threads.submit(() -> {
            authenticateAsAlice();
            try {
                start.await();
                return jobManagement.cancelJob(jobId).outcome();
            } catch (JobNotCancellableException e) {
                return e;
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    void cancellationRacingCompletionAlwaysEndsConsistently() throws Exception {
        int cancelledFirst = 0;
        int completedFirst = 0;
        for (int i = 0; i < 40; i++) {
            rows.job(alice.id()).insert();
            ClaimedJob claimed = lifecycle.claimNext("worker-a").orElseThrow();
            CountDownLatch start = new CountDownLatch(1);

            Future<Object> cancel = cancelAsync(claimed.jobId(), start);
            Future<RecordedOutcome> complete = threads.submit((Callable<RecordedOutcome>) () -> {
                start.await();
                return lifecycle.complete(claimed);
            });
            start.countDown();
            Object cancelResult = cancel.get(10, TimeUnit.SECONDS);
            RecordedOutcome completion = complete.get(10, TimeUnit.SECONDS);

            Map<String, Object> job = jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", claimed.jobId());
            String attempt = jdbc.queryForObject("SELECT status FROM job_attempts WHERE job_id = ?", String.class, claimed.jobId());
            if (cancelResult == CancelOutcome.CANCEL_REQUESTED) {
                // Cancellation committed first: the completion must not commit COMPLETED.
                cancelledFirst++;
                assertThat(completion).isEqualTo(RecordedOutcome.CANCELLED);
                assertThat(job).containsEntry("status", "CANCELLED");
                assertThat(job.get("completed_at")).isNull();
                assertThat(attempt).isEqualTo("CANCELLED");
            } else {
                // Completion committed first: the cancellation observes a completed job.
                completedFirst++;
                assertThat(cancelResult).isInstanceOf(JobNotCancellableException.class);
                assertThat(completion).isEqualTo(RecordedOutcome.COMPLETED);
                assertThat(job).containsEntry("status", "COMPLETED");
                assertThat(job.get("cancel_requested_at")).isNull();
                assertThat(attempt).isEqualTo("SUCCEEDED");
            }
            assertThat(job.get("worker_id")).isNull();
            assertThat(job.get("finished_at")).isNotNull();
        }
        System.out.printf("Cancellation/completion race: cancellation first %d times, completion first %d times%n",
                cancelledFirst, completedFirst);
        assertThat(cancelledFirst + completedFirst).isEqualTo(40);
    }

    @Test
    void cancellationArrivingDuringAClaimWaitsAndThenStopsTheRunningJob() throws Exception {
        UUID id = rows.job(alice.id()).insert();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch commitClaim = new CountDownLatch(1);
        Future<?> claimTx = threads.submit(() -> transactions.executeWithoutResult(status -> {
            lifecycle.claimNext("worker-a").orElseThrow();
            claimed.countDown();
            await(commitClaim);
        }));
        assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Object> cancel = cancelAsync(id, new CountDownLatch(0));
        Thread.sleep(300);
        assertThat(cancel.isDone()).as("the cancellation waits for the claim's row lock").isFalse();

        commitClaim.countDown();
        claimTx.get(5, TimeUnit.SECONDS);

        assertThat(cancel.get(5, TimeUnit.SECONDS)).isEqualTo(CancelOutcome.CANCEL_REQUESTED);
        assertThat(jdbc.queryForMap("SELECT status, worker_id FROM jobs WHERE id = ?", id))
                .containsEntry("status", "CANCEL_REQUESTED").containsEntry("worker_id", "worker-a");
    }

    @Test
    void claimDuringACancellationSkipsTheJobWhichEndsCancelled() throws Exception {
        UUID id = rows.job(alice.id()).insert();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commitCancel = new CountDownLatch(1);
        Future<?> cancelTx = threads.submit(() -> {
            authenticateAsAlice();
            try {
                transactions.executeWithoutResult(status -> {
                    jobManagement.cancelJob(id);
                    locked.countDown();
                    await(commitCancel);
                });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThat(lifecycle.claimNext("worker-a")).as("locked row is skipped").isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));

        commitCancel.countDown();
        cancelTx.get(5, TimeUnit.SECONDS);

        assertThat(lifecycle.claimNext("worker-a")).as("a cancelled job is never claimed").isEmpty();
        assertThat(jdbc.queryForMap("SELECT status, fencing_token FROM jobs WHERE id = ?", id))
                .containsEntry("status", "CANCELLED").containsEntry("fencing_token", 0L);
    }

    private void authenticateAsAlice() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .subject(alice.id().toString()).claim("username", alice.username()).claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        List<GrantedAuthority> authorities = Arrays.stream(Permission.values())
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name())).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities, alice.id().toString()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
