package com.example.jobprocessor.job.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.TestProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link TestProperties}: 3 retries, backoff 5s/10s/20s, jitter ±20%. */
class RetryPolicyTest {

    private final RetryPolicy noJitter = new RetryPolicy(TestProperties.create(false), () -> 0.5);

    @Test
    void retriesUntilTheBudgetIsUsed() {
        assertThat(noJitter.hasRetriesLeft(0, 3)).isTrue();
        assertThat(noJitter.hasRetriesLeft(2, 3)).isTrue();
        assertThat(noJitter.hasRetriesLeft(3, 3)).isFalse();
        assertThat(noJitter.hasRetriesLeft(0, 0)).isFalse();
    }

    @Test
    void usesTheConfiguredBackoffPerRetry() {
        assertThat(noJitter.delayBeforeRetry(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(noJitter.delayBeforeRetry(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(noJitter.delayBeforeRetry(3)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void reusesTheLastDelayForLaterRetries() {
        assertThat(noJitter.delayBeforeRetry(7)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void jitterStaysWithinTheConfiguredFactor() {
        RetryPolicy lowest = new RetryPolicy(TestProperties.create(false), () -> 0.0);
        RetryPolicy highest = new RetryPolicy(TestProperties.create(false), () -> 0.999_999);

        assertThat(lowest.delayBeforeRetry(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(highest.delayBeforeRetry(2)).isBetween(Duration.ofMillis(11_999), Duration.ofSeconds(12));
    }

    @Test
    void realRandomJitterIsBounded() {
        RetryPolicy policy = new RetryPolicy(TestProperties.create(false));
        for (int i = 0; i < 1000; i++) {
            assertThat(policy.delayBeforeRetry(1)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
        }
    }

    @Test
    void retryNumbersStartAtOne() {
        assertThatThrownBy(() -> noJitter.delayBeforeRetry(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
