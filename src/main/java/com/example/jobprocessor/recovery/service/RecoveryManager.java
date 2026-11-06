package com.example.jobprocessor.recovery.service;

import com.example.jobprocessor.job.service.JobLifecycleService;
import com.example.jobprocessor.job.service.RecoveredJob;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Recovers jobs whose lease expired (README §8.8, §13). Every instance runs one; they never take
 * the same job, because each recovery locks its row with SKIP LOCKED inside Job Management's
 * recovery operation. An expired lease is not proof that the old worker stopped, so recovery relies
 * on fencing: it clears the old ownership, and the old worker's writes no longer match.
 */
@Service
public class RecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    /** Upper bound per run, so one run cannot monopolize the recovery thread. */
    static final int MAX_JOBS_PER_RUN = 100;

    private final JobLifecycleService lifecycle;

    public RecoveryManager(JobLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** Recovers expired leases one job (one transaction) at a time until none are left. */
    public List<RecoveredJob> recoverExpiredLeases() {
        List<RecoveredJob> recovered = new ArrayList<>();
        try {
            Optional<RecoveredJob> next;
            while (recovered.size() < MAX_JOBS_PER_RUN && (next = lifecycle.recoverNextExpiredLease()).isPresent()) {
                recovered.add(next.get());
            }
        } catch (RuntimeException e) {
            log.warn("Recovery run stopped early, will retry next run: {}", e.getMessage());
        }
        if (!recovered.isEmpty()) {
            log.info("Recovery run finished: {} job(s) recovered", recovered.size());
        }
        return recovered;
    }
}
