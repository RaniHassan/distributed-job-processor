package com.example.jobprocessor.job.lifecycle;

import static com.example.jobprocessor.job.domain.JobStatus.CANCELLED;
import static com.example.jobprocessor.job.domain.JobStatus.CANCEL_REQUESTED;
import static com.example.jobprocessor.job.domain.JobStatus.COMPLETED;
import static com.example.jobprocessor.job.domain.JobStatus.FAILED;
import static com.example.jobprocessor.job.domain.JobStatus.PENDING;
import static com.example.jobprocessor.job.domain.JobStatus.RUNNING;

import com.example.jobprocessor.job.domain.JobStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only legal job state transitions (README §9). Job Management checks every transition here
 * before issuing the conditional update that applies it, so the expected "from" status in each
 * SQL statement always comes from a validated transition.
 */
public final class JobStateMachine {

    private static final Map<JobStatus, Set<JobStatus>> ALLOWED = new EnumMap<>(JobStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(RUNNING, CANCELLED));
        // RUNNING -> PENDING covers both a scheduled retry and recovery of an abandoned job.
        ALLOWED.put(RUNNING, EnumSet.of(COMPLETED, FAILED, CANCEL_REQUESTED, PENDING));
        ALLOWED.put(CANCEL_REQUESTED, EnumSet.of(CANCELLED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(JobStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(JobStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(JobStatus.class));
    }

    private JobStateMachine() {
    }

    public static boolean canTransition(JobStatus from, JobStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void requireTransition(JobStatus from, JobStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }

    public static Set<JobStatus> allowedTargets(JobStatus from) {
        return Set.copyOf(ALLOWED.get(from));
    }
}
