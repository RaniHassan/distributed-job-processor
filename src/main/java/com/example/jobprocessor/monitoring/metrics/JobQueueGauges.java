package com.example.jobprocessor.monitoring.metrics;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.common.util.FixedDelayLoop;
import com.example.jobprocessor.job.domain.JobStatus;
import com.example.jobprocessor.job.repository.JobStateRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide queue gauges ({@code jobs.pending}, {@code jobs.running}) read from PostgreSQL on a
 * fixed interval, so scraping metrics never queries the database. Every instance reports the same
 * cluster-wide value: aggregate with max, not sum.
 */
@Component
public class JobQueueGauges implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobQueueGauges.class);

    private final JobStateRepository jobStates;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong running = new AtomicLong();
    private final FixedDelayLoop loop;

    public JobQueueGauges(JobStateRepository jobStates, MeterRegistry registry, JobProcessorProperties properties) {
        this.jobStates = jobStates;
        Gauge.builder("jobs.pending", pending, AtomicLong::get)
                .description("PENDING jobs in the whole cluster (refreshed periodically)")
                .register(registry);
        Gauge.builder("jobs.running", running, AtomicLong::get)
                .description("Jobs being executed in the whole cluster, incl. cancellation in progress (refreshed periodically)")
                .register(registry);
        this.loop = new FixedDelayLoop("job-queue-gauges", properties.metrics().queueGaugeRefreshInterval(), this::refresh);
    }

    public void refresh() {
        try {
            Map<JobStatus, Long> counts = jobStates.countActiveJobs();
            pending.set(counts.getOrDefault(JobStatus.PENDING, 0L));
            running.set(counts.getOrDefault(JobStatus.RUNNING, 0L) + counts.getOrDefault(JobStatus.CANCEL_REQUESTED, 0L));
        } catch (RuntimeException e) {
            // Keep the last known values; a stale gauge is better than a failing scrape.
            log.warn("Could not refresh job queue gauges: {}", e.getMessage());
        }
    }

    @Override
    public void start() {
        loop.start();
    }

    @Override
    public void stop() {
        loop.stop();
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }
}
