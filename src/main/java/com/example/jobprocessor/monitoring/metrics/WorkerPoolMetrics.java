package com.example.jobprocessor.monitoring.metrics;

import com.example.jobprocessor.worker.pool.WorkerPool;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** Local worker pool gauges: what this instance is running right now. */
@Component
public class WorkerPoolMetrics implements MeterBinder {

    private final WorkerPool pool;

    public WorkerPoolMetrics(WorkerPool pool) {
        this.pool = pool;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("worker.pool.active.threads", pool, WorkerPool::activeCount)
                .description("Worker slots in use on this instance")
                .register(registry);
        Gauge.builder("worker.pool.capacity", pool, WorkerPool::capacity)
                .description("Worker threads on this instance")
                .register(registry);
        Gauge.builder("worker.pool.queue.size", pool, WorkerPool::queueSize)
                .description("Tasks waiting in the executor queue (expected to stay at or near 0)")
                .register(registry);
    }
}
