package com.example.jobprocessor.job.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.ApiIntegrationTest;
import com.example.jobprocessor.TestRows;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Heartbeat lease renewal and cancellation lookup against real PostgreSQL (README §11). */
@ApiIntegrationTest
class JobLeaseIT {

    @Autowired
    private JobLifecycleService lifecycle;
    @Autowired
    private TestRows rows;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID owner;

    @BeforeEach
    void cleanDatabase() {
        rows.deleteAllJobs();
        owner = rows.insertUser();
    }

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("SELECT * FROM jobs WHERE id = ?", id);
    }

    private ClaimedJob claim(String workerId) {
        return lifecycle.claimNext(workerId).orElseThrow();
    }

    @Test
    void ownerRenewsItsLeaseAndHeartbeat() throws Exception {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = claim("worker-a");
        Timestamp leaseBefore = (Timestamp) job(id).get("lease_until");
        Timestamp heartbeatBefore = (Timestamp) job(id).get("heartbeat_at");
        Thread.sleep(50);

        assertThat(lifecycle.renewLease(claimed)).isEqualTo(LeaseStatus.RENEWED);

        Map<String, Object> row = job(id);
        assertThat((Timestamp) row.get("lease_until")).isAfter(leaseBefore);
        assertThat((Timestamp) row.get("heartbeat_at")).isAfter(heartbeatBefore);
        assertThat(row).containsEntry("status", "RUNNING").containsEntry("worker_id", "worker-a");
    }

    @Test
    void cancellationRequestIsReportedAndTheLeaseIsNotExtended() {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = claim("worker-a");
        jdbc.update("UPDATE jobs SET status = 'CANCEL_REQUESTED', cancel_requested_at = now() WHERE id = ?", id);
        Object leaseBefore = job(id).get("lease_until");

        assertThat(lifecycle.renewLease(claimed)).isEqualTo(LeaseStatus.CANCEL_REQUESTED);
        assertThat(job(id).get("lease_until")).isEqualTo(leaseBefore);
    }

    @Test
    void staleGenerationAndOtherWorkersCannotRenew() {
        UUID id = rows.job(owner).insert();
        ClaimedJob stale = claim("worker-a");
        lifecycle.releaseClaim(stale, "simulated takeover");
        ClaimedJob current = claim("worker-b");
        Object leaseBefore = job(id).get("lease_until");

        assertThat(lifecycle.renewLease(stale)).isEqualTo(LeaseStatus.LOST);
        assertThat(lifecycle.renewLease(new ClaimedJob(id, "worker-x", current.fencingToken(), Map.of())))
                .isEqualTo(LeaseStatus.LOST);
        assertThat(job(id).get("lease_until")).isEqualTo(leaseBefore);
        assertThat(lifecycle.renewLease(current)).isEqualTo(LeaseStatus.RENEWED);
    }

    @Test
    void finishedJobsReportLostOwnership() {
        rows.job(owner).insert();
        ClaimedJob claimed = claim("worker-a");
        lifecycle.complete(claimed);

        assertThat(lifecycle.renewLease(claimed)).isEqualTo(LeaseStatus.LOST);
    }

    @Test
    void cancellationLookupMatchesWorkerAndGeneration() {
        for (int i = 0; i < 3; i++) {
            rows.job(owner).insert();
        }
        ClaimedJob cancelledClaim = claim("worker-a");
        ClaimedJob runningClaim = claim("worker-a");
        ClaimedJob foreignClaim = claim("worker-b");
        for (UUID id : List.of(cancelledClaim.jobId(), foreignClaim.jobId())) {
            jdbc.update("UPDATE jobs SET status = 'CANCEL_REQUESTED', cancel_requested_at = now() WHERE id = ?", id);
        }
        ClaimedJob staleView = new ClaimedJob(cancelledClaim.jobId(), "worker-a", 99, Map.of());

        assertThat(lifecycle.findCancellationRequests("worker-a", List.of(cancelledClaim, runningClaim)))
                .containsExactly(cancelledClaim.jobId());
        assertThat(lifecycle.findCancellationRequests("worker-a", List.of(foreignClaim))).as("not this worker's job").isEmpty();
        assertThat(lifecycle.findCancellationRequests("worker-a", List.of(staleView))).as("different generation").isEmpty();
        assertThat(lifecycle.findCancellationRequests("worker-a", List.of())).isEmpty();
    }

    @Test
    void renewalKeepsAJobAwayFromRecovery() throws Exception {
        UUID id = rows.job(owner).insert();
        ClaimedJob claimed = claim("worker-a");
        // Make the lease nearly expired, then renew it: recovery must not see it as expired.
        jdbc.update("UPDATE jobs SET lease_until = now() + interval '100 milliseconds' WHERE id = ?", id);
        lifecycle.renewLease(claimed);
        Thread.sleep(200);

        assertThat(lifecycle.recoverNextExpiredLease()).isEmpty();
        assertThat(job(id)).containsEntry("status", "RUNNING").containsEntry("worker_id", "worker-a");
    }

    @Test
    void leaseIsSetWithTheDatabaseClock() {
        UUID id = rows.job(owner).insert();
        claim("worker-a");

        Boolean consistent = jdbc.queryForObject("""
                SELECT lease_until - heartbeat_at BETWEEN interval '1.9 seconds' AND interval '2.1 seconds'
                FROM jobs WHERE id = ?""", Boolean.class, id);
        assertThat(consistent).as("lease = heartbeat_at + 2s test lease").isTrue();
    }
}
