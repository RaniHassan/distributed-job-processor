package com.example.jobprocessor.job.repository;

import com.example.jobprocessor.job.domain.Job;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and inserts jobs. Ownership is part of every user-facing lookup, so a job owned by someone
 * else is indistinguishable from a missing one. State transitions do not go through this
 * repository.
 */
public interface JobRepository extends JpaRepository<Job, UUID> {

    Optional<Job> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByIdAndOwnerId(UUID id, UUID ownerId);

    Page<Job> findByOwnerId(UUID ownerId, Pageable pageable);
}
