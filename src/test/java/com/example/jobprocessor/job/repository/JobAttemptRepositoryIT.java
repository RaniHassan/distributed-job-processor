package com.example.jobprocessor.job.repository;

import static com.example.jobprocessor.TestRows.now;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.PostgresRepositoryTest;
import com.example.jobprocessor.TestRows;
import com.example.jobprocessor.job.domain.AttemptStatus;
import com.example.jobprocessor.job.domain.JobAttempt;
import com.example.jobprocessor.job.dto.JobAttemptResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresRepositoryTest
class JobAttemptRepositoryIT {

    @Autowired
    private JobAttemptRepository attemptRepository;

    @Autowired
    private TestRows rows;

    @Test
    void returnsAttemptHistoryInOrder() {
        UUID ownerId = rows.insertUser();
        UUID jobId = rows.job(ownerId).running().set("fencing_token", 3L).set("retry_count", 2).insert();
        UUID otherJob = rows.job(ownerId).running().insert();
        rows.attempt(jobId, 3).set("worker_id", "worker-c").insert();
        rows.attempt(jobId, 1).set("status", "FAILED").set("finished_at", now()).set("error_message", "timeout").insert();
        rows.attempt(jobId, 2).set("status", "ABANDONED").set("finished_at", now()).insert();
        rows.attempt(otherJob, 1).insert();

        List<JobAttempt> attempts = attemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);

        assertThat(attempts).extracting(JobAttempt::getAttemptNumber).containsExactly(1, 2, 3);
        assertThat(attempts).extracting(JobAttempt::getStatus)
                .containsExactly(AttemptStatus.FAILED, AttemptStatus.ABANDONED, AttemptStatus.RUNNING);
        JobAttempt current = attempts.get(2);
        assertThat(current.getWorkerId()).isEqualTo("worker-c");
        assertThat(current.getFencingToken()).isEqualTo(3L);
        assertThat(current.getFinishedAt()).isNull();
        assertThat(attempts.get(0).getErrorMessage()).isEqualTo("timeout");
    }

    @Test
    void findsAttemptByNumber() {
        UUID jobId = rows.job(rows.insertUser()).running().insert();
        rows.attempt(jobId, 1).insert();

        assertThat(attemptRepository.findByJobIdAndAttemptNumber(jobId, 1)).isPresent();
        assertThat(attemptRepository.findByJobIdAndAttemptNumber(jobId, 2)).isEmpty();
    }

    @Test
    void attemptResponseOmitsWorkerAndToken() {
        UUID jobId = rows.job(rows.insertUser()).running().insert();
        rows.attempt(jobId, 1).insert();

        JobAttemptResponse response =
                JobAttemptResponse.from(attemptRepository.findByJobIdAndAttemptNumber(jobId, 1).orElseThrow());

        assertThat(response.attemptNumber()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(AttemptStatus.RUNNING);
        assertThat(JobAttemptResponse.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("workerId", "fencingToken", "jobId");
    }
}
