package com.example.jobprocessor.job.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.PostgresRepositoryTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.job.domain.Job;
import com.example.jobprocessor.job.domain.JobStatus;
import com.example.jobprocessor.job.dto.JobResponse;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@PostgresRepositoryTest
class JobRepositoryIT {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRows rows;

    private UUID ownerId;

    @BeforeEach
    void createOwner() {
        ownerId = rows.insertUser();
    }

    @Test
    void persistsNewPendingJobWithDatabaseGeneratedTimestamps() {
        Job saved = jobRepository.saveAndFlush(Job.newPending(ownerId, Map.of("someParameter", "someValue"), 3));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getNextRetryAt()).isEqualTo(saved.getCreatedAt());

        entityManager.clear();
        Job loaded = jobRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getOwnerId()).isEqualTo(ownerId);
        assertThat(loaded.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(loaded.getRetryCount()).isZero();
        assertThat(loaded.getMaxRetries()).isEqualTo(3);
        assertThat(loaded.getFencingToken()).isZero();
        assertThat(loaded.getWorkerId()).isNull();
        assertThat(loaded.getLeaseUntil()).isNull();
        assertThat(loaded.getStartedAt()).isNull();
        assertThat(loaded.getCompletedAt()).isNull();
        assertThat(loaded.getFinishedAt()).isNull();
    }

    @Test
    void hibernateNeverWritesJobChanges() {
        UUID id = jobRepository.saveAndFlush(Job.newPending(ownerId, Map.of("k", "v"), 3)).getId();
        Job managed = jobRepository.findById(id).orElseThrow();

        // Simulate code that mutates a managed job; status changes must only happen via conditional SQL.
        ReflectionTestUtils.setField(managed, "status", JobStatus.COMPLETED);
        ReflectionTestUtils.setField(managed, "workerId", "rogue-worker");
        ReflectionTestUtils.setField(managed, "fencingToken", 99L);
        jobRepository.saveAndFlush(managed);
        entityManager.clear();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, worker_id, fencing_token FROM jobs WHERE id = ?", id);
        assertThat(row).containsEntry("status", "PENDING").containsEntry("fencing_token", 0L);
        assertThat(row.get("worker_id")).isNull();
    }

    @Test
    void roundTripsNestedJsonPayloadIncludingNulls() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "report");
        payload.put("count", 42);
        payload.put("tags", List.of("a", "b"));
        payload.put("options", Map.of("deep", true));
        payload.put("nothing", null);

        UUID id = jobRepository.saveAndFlush(Job.newPending(ownerId, payload, 3)).getId();
        entityManager.clear();

        assertThat(jobRepository.findById(id).orElseThrow().getPayload()).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(payload) FROM jobs WHERE id = ?", String.class, id))
                .isEqualTo("object");
    }

    @Test
    void readsOwnedAndTerminalStatesWrittenBySql() {
        UUID running = rows.job(ownerId).running().insert();
        UUID completed = rows.job(ownerId).completed().insert();

        Job runningJob = jobRepository.findById(running).orElseThrow();
        assertThat(runningJob.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(runningJob.getWorkerId()).isEqualTo("worker-a");
        assertThat(runningJob.getLeaseUntil()).isNotNull();
        assertThat(runningJob.getFencingToken()).isEqualTo(1L);

        Job completedJob = jobRepository.findById(completed).orElseThrow();
        assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completedJob.getCompletedAt()).isNotNull();
        assertThat(completedJob.getFinishedAt()).isNotNull();
    }

    @Test
    void ownerScopedLookupHidesOtherUsersJobs() {
        UUID otherOwner = rows.insertUser();
        UUID jobId = rows.job(ownerId).insert();

        assertThat(jobRepository.findByIdAndOwnerId(jobId, ownerId)).isPresent();
        assertThat(jobRepository.findByIdAndOwnerId(jobId, otherOwner)).isEmpty();
        assertThat(jobRepository.findByIdAndOwnerId(UUID.randomUUID(), ownerId)).isEmpty();
    }

    @Test
    void listsOnlyOwnJobsNewestFirstWithPaging() {
        UUID otherOwner = rows.insertUser();
        OffsetDateTime base = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        UUID[] ids = new UUID[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = rows.job(ownerId).set("created_at", base.plusMinutes(i)).insert();
        }
        rows.job(otherOwner).set("created_at", base.plusHours(1)).insert();

        Page<Job> first = jobRepository.findByOwnerId(ownerId, PageRequest.of(0, 2, Sort.by("createdAt").descending()));
        Page<Job> last = jobRepository.findByOwnerId(ownerId, PageRequest.of(2, 2, Sort.by("createdAt").descending()));

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getContent()).extracting(Job::getId).containsExactly(ids[4], ids[3]);
        assertThat(last.getContent()).extracting(Job::getId).containsExactly(ids[0]);
    }

    @Test
    void responseMapsPublicFieldsOnly() {
        UUID id = rows.job(ownerId).failed().set("retry_count", 3).insert();

        JobResponse response = JobResponse.from(jobRepository.findById(id).orElseThrow());

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(JobStatus.FAILED);
        assertThat(response.retryCount()).isEqualTo(3);
        assertThat(response.maxRetries()).isEqualTo(3);
        assertThat(response.error()).isEqualTo("boom");
        assertThat(response.completedAt()).isNull();
        assertThat(response.finishedAt()).isNotNull();
        assertThat(Arrays.stream(JobResponse.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("workerId", "leaseUntil", "fencingToken", "ownerId", "payload");
    }
}
