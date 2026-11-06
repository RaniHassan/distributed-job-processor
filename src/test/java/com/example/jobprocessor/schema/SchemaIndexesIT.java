package com.example.jobprocessor.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.PostgresRepositoryTest;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Checks the index set and that the planner can use each partial index for the query shape it
 * exists for. Sequential scans are disabled inside the (rolled-back) test transaction so an empty
 * table still reveals whether the index matches the query's predicate.
 */
@PostgresRepositoryTest
class SchemaIndexesIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void preferIndexes() {
        jdbc.execute("SET LOCAL enable_seqscan = off");
    }

    @Test
    void migrationIsApplied() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).hasSize(1);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void jobsHaveExactlyTheDesignedIndexes() {
        assertThat(indexesOf("jobs")).containsExactlyInAnyOrder(
                "jobs_pkey", "idx_jobs_pending", "idx_jobs_expired_lease", "idx_jobs_owner_created");
    }

    @Test
    void attemptsHaveNoRedundantJobIndex() {
        assertThat(indexesOf("job_attempts")).containsExactlyInAnyOrder(
                "job_attempts_pkey", "uq_job_attempts_job_attempt", "uq_job_attempts_one_running");
    }

    @Test
    void idempotencyKeysHaveUniqueKeyAndExpiryIndexes() {
        assertThat(indexesOf("idempotency_keys")).containsExactlyInAnyOrder(
                "idempotency_keys_pkey", "uq_idempotency_owner_key", "idx_idempotency_expires");
    }

    @Test
    void expiredLeaseIndexCoversRunningAndCancelRequested() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_jobs_expired_lease'", String.class);

        assertThat(definition).contains("RUNNING").contains("CANCEL_REQUESTED");
    }

    @Test
    void claimQueryUsesPendingIndex() {
        assertThat(plan("""
                SELECT id FROM jobs
                WHERE status = 'PENDING' AND next_retry_at <= now()
                ORDER BY next_retry_at, created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED"""))
                .contains("idx_jobs_pending")
                .doesNotContain("Sort");
    }

    @Test
    void recoveryQueryUsesExpiredLeaseIndex() {
        assertThat(plan("""
                SELECT id FROM jobs
                WHERE status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until < now()
                FOR UPDATE SKIP LOCKED"""))
                .contains("idx_jobs_expired_lease");
    }

    /** The Recovery Manager's exact query: oldest expired lease first, one row, skip locked. */
    @Test
    void recoveryClaimQueryUsesExpiredLeaseIndexWithoutSorting() {
        assertThat(plan("""
                SELECT id, status, worker_id, fencing_token, retry_count, max_retries
                FROM jobs
                WHERE status IN ('RUNNING', 'CANCEL_REQUESTED') AND lease_until < now()
                ORDER BY lease_until
                LIMIT 1
                FOR UPDATE SKIP LOCKED"""))
                .contains("idx_jobs_expired_lease")
                .doesNotContain("Sort");
    }

    @Test
    void ownerListingUsesOwnerIndexWithoutSorting() {
        assertThat(plan("""
                SELECT id FROM jobs
                WHERE owner_id = '00000000-0000-0000-0000-000000000001'
                ORDER BY created_at DESC
                LIMIT 20"""))
                .contains("idx_jobs_owner_created")
                .doesNotContain("Sort");
    }

    @Test
    void idempotencyCleanupUsesExpiryIndex() {
        assertThat(plan("DELETE FROM idempotency_keys WHERE expires_at < now()"))
                .contains("idx_idempotency_expires");
    }

    private List<String> indexesOf(String table) {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?", String.class, table);
    }

    private String plan(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }
}
