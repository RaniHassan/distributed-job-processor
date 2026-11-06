package com.example.jobprocessor.worker.pool;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The worker id this process writes to {@code jobs.worker_id}: the configured instance id plus a
 * random suffix per process start. A restarted instance therefore never shares an identity with
 * its previous run, so ownership from before a crash can only be recovered, never resumed.
 */
@Component
public class WorkerIdentity {

    private static final Logger log = LoggerFactory.getLogger(WorkerIdentity.class);

    private final String workerId;

    public WorkerIdentity(JobProcessorProperties properties) {
        this.workerId = properties.worker().instanceId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Worker identity: {}", workerId);
    }

    public String workerId() {
        return workerId;
    }
}
