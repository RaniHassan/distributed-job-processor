package com.example.jobprocessor.job.repository;

import com.example.jobprocessor.job.domain.JobAttempt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {

    List<JobAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);

    Optional<JobAttempt> findByJobIdAndAttemptNumber(UUID jobId, int attemptNumber);
}
