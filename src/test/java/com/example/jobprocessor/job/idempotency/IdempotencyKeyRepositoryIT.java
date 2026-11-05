package com.example.jobprocessor.job.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.PostgresRepositoryTest;
import com.example.jobprocessor.TestRows;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresRepositoryTest
class IdempotencyKeyRepositoryIT {

    @Autowired
    private IdempotencyKeyRepository repository;

    @Autowired
    private TestRows rows;

    @Test
    void findsKeyScopedToOwner() {
        UUID ownerId = rows.insertUser();
        UUID otherOwner = rows.insertUser();
        UUID jobId = rows.job(ownerId).insert();
        rows.idempotencyKey(ownerId, jobId, "order-42").insert();

        IdempotencyKey key = repository.findByOwnerIdAndKey(ownerId, "order-42").orElseThrow();

        assertThat(key.getJobId()).isEqualTo(jobId);
        assertThat(key.getRequestHash()).hasSize(64);
        assertThat(key.getExpiresAt()).isAfter(key.getCreatedAt());
        assertThat(repository.findByOwnerIdAndKey(otherOwner, "order-42")).isEmpty();
        assertThat(repository.findByOwnerIdAndKey(ownerId, "order-43")).isEmpty();
    }
}
