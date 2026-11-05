package com.example.jobprocessor.job.idempotency;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Periodically deletes idempotency keys past their retention period. Every instance runs it;
 * concurrent runs skip each other's locked rows, and only expired keys are ever deleted.
 */
@Component
public class IdempotencyKeyCleanup implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);
    static final int BATCH_SIZE = 1000;

    private final IdempotencyKeyStore keyStore;
    private final Duration interval;

    public IdempotencyKeyCleanup(IdempotencyKeyStore keyStore, JobProcessorProperties properties) {
        this.keyStore = keyStore;
        this.interval = properties.idempotency().cleanupInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::purgeExpired, interval, interval));
    }

    public int purgeExpired() {
        int total = 0;
        try {
            int deleted;
            do {
                deleted = keyStore.deleteExpired(BATCH_SIZE);
                total += deleted;
            } while (deleted == BATCH_SIZE);
        } catch (DataAccessException e) {
            log.warn("Idempotency key cleanup failed, will retry in {}: {}", interval, e.getMessage());
        }
        if (total > 0) {
            log.info("Deleted {} expired idempotency keys", total);
        }
        return total;
    }
}
