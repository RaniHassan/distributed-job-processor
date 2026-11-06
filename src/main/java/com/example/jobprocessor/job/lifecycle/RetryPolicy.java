package com.example.jobprocessor.job.lifecycle;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Retry budget and backoff (README §12): {@code maxRetries} retries after the initial attempt,
 * waiting {@code backoff[n-1]} before retry n (the last delay is reused when there are more retries
 * than delays), spread by ±{@code jitterFactor} so jobs that failed together do not all come back
 * at the same instant. The budget itself is stored per job ({@code jobs.max_retries}).
 */
@Component
public class RetryPolicy {

    private final List<Duration> backoff;
    private final double jitterFactor;
    private final DoubleSupplier random;

    @Autowired
    public RetryPolicy(JobProcessorProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** @param random source of values in [0, 1) */
    public RetryPolicy(JobProcessorProperties properties, DoubleSupplier random) {
        this.backoff = List.copyOf(properties.retry().backoff());
        this.jitterFactor = properties.retry().jitterFactor();
        this.random = random;
    }

    public boolean hasRetriesLeft(int retryCount, int maxRetries) {
        return retryCount < maxRetries;
    }

    /**
     * Whether a failed attempt is retried: transient failures and unexpected ones (an explicit,
     * bounded policy, never indefinite) while the budget lasts; permanent failures never.
     */
    public boolean shouldRetry(FailureType type, int retryCount, int maxRetries) {
        return type != FailureType.PERMANENT && hasRetriesLeft(retryCount, maxRetries);
    }

    /** Delay before retry number {@code retryNumber} (1 for the first retry). */
    public Duration delayBeforeRetry(int retryNumber) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("retryNumber starts at 1");
        }
        Duration base = backoff.get(Math.min(retryNumber, backoff.size()) - 1);
        double factor = 1 + jitterFactor * (2 * random.getAsDouble() - 1); // in [1 - jitter, 1 + jitter)
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
    }
}
