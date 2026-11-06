package com.example.jobprocessor.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Operational settings of the job processor (README §20). Bound from the {@code jobprocessor.*}
 * namespace and validated at startup, so an instance with incompatible timing values never starts.
 */
@Validated
@ConfigurationProperties(prefix = "jobprocessor")
public record JobProcessorProperties(
        @Valid @NotNull Worker worker,
        @Valid @NotNull Dispatcher dispatcher,
        @Valid @NotNull Lease lease,
        @Valid @NotNull Recovery recovery,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Simulation simulation,
        @Valid @NotNull Shutdown shutdown,
        @Valid @NotNull Idempotency idempotency,
        @Valid @NotNull Api api,
        @Valid @NotNull Security security) {

    /**
     * Heartbeats only renew RUNNING jobs, so a cancelling worker must notice the request well
     * before its lease runs out; checking more often than heartbeats are sent guarantees that.
     */
    @AssertTrue(message = "worker.cancellation-check-interval must be shorter than lease.heartbeat-interval")
    public boolean isCancellationCheckFasterThanHeartbeat() {
        if (worker == null || lease == null || !isPositive(worker.cancellationCheckInterval())
                || !isPositive(lease.heartbeatInterval())) {
            return true; // reported by the field constraints
        }
        return worker.cancellationCheckInterval().compareTo(lease.heartbeatInterval()) < 0;
    }

    /**
     * @param instanceId stable name of this instance (e.g. the container name); a random suffix is
     *                   added per process start to form the worker id stored on claimed jobs
     */
    public record Worker(
            @Min(1) @Max(256) int threads,
            @NotNull Duration cancellationCheckInterval,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}",
                    message = "must be 1-100 characters of letters, digits, '.', '_' or '-'") String instanceId) {

        @AssertTrue(message = "must be positive")
        public boolean isCancellationCheckIntervalPositive() {
            return cancellationCheckInterval == null || isPositive(cancellationCheckInterval);
        }
    }

    /** @param enabled whether this instance claims and executes jobs (the API works either way) */
    public record Dispatcher(boolean enabled, @NotNull Duration pollInterval) {

        @AssertTrue(message = "must be positive")
        public boolean isPollIntervalPositive() {
            return pollInterval == null || isPositive(pollInterval);
        }
    }

    public record Lease(@NotNull Duration duration, @NotNull Duration heartbeatInterval) {

        @AssertTrue(message = "must be positive")
        public boolean isHeartbeatIntervalPositive() {
            return heartbeatInterval == null || isPositive(heartbeatInterval);
        }

        /**
         * A lease must survive at least one missed heartbeat; otherwise a single slow database
         * round-trip would let Recovery take a job away from a healthy worker.
         */
        @AssertTrue(message = "lease.duration must be longer than twice lease.heartbeat-interval")
        public boolean isDurationCompatibleWithHeartbeat() {
            if (duration == null || heartbeatInterval == null) {
                return true;
            }
            return duration.compareTo(heartbeatInterval.multipliedBy(2)) > 0;
        }
    }

    /** @param enabled whether this instance runs the Recovery Manager (safe to run on every instance) */
    public record Recovery(boolean enabled, @NotNull Duration pollInterval) {

        @AssertTrue(message = "must be positive")
        public boolean isPollIntervalPositive() {
            return pollInterval == null || isPositive(pollInterval);
        }
    }

    /**
     * Retry policy. {@code backoff} lists the delay before each retry; when there are more
     * retries than entries, the last delay is reused.
     */
    public record Retry(
            @Min(0) @Max(100) int maxRetries,
            @NotEmpty List<@NotNull Duration> backoff,
            @DecimalMin("0.0") @DecimalMax("1.0") double jitterFactor) {

        @AssertTrue(message = "backoff delays must not be negative")
        public boolean isBackoffNonNegative() {
            return backoff == null || backoff.stream().allMatch(d -> d == null || !d.isNegative());
        }
    }

    /**
     * Simulated job execution. Failure injection lets a client force transient or permanent
     * failures; {@link FailureInjectionGuard} keeps it out of production.
     */
    public record Simulation(
            @NotNull Duration minDuration,
            @NotNull Duration maxDuration,
            boolean failureInjectionEnabled) {

        @AssertTrue(message = "simulation.min-duration must be positive and not longer than simulation.max-duration")
        public boolean isDurationRangeValid() {
            if (minDuration == null || maxDuration == null) {
                return true;
            }
            return isPositive(minDuration) && minDuration.compareTo(maxDuration) <= 0;
        }
    }

    public record Shutdown(@NotNull Duration timeout) {

        @AssertTrue(message = "must not be negative")
        public boolean isTimeoutNonNegative() {
            return timeout == null || !timeout.isNegative();
        }
    }

    public record Idempotency(@NotNull Duration retention, @NotNull Duration cleanupInterval) {

        @AssertTrue(message = "retention and cleanup-interval must be positive")
        public boolean isPositiveDurations() {
            return (retention == null || isPositive(retention))
                    && (cleanupInterval == null || isPositive(cleanupInterval));
        }
    }

    /** Limits applied to every API request before it reaches a controller. */
    public record Api(
            @NotNull DataSize maxRequestSize,
            @Min(2) @Max(1000) int maxJsonNestingDepth) {

        @AssertTrue(message = "api.max-request-size must be positive")
        public boolean isMaxRequestSizePositive() {
            return maxRequestSize == null || maxRequestSize.toBytes() > 0;
        }
    }

    public record Security(@Valid @NotNull JwtSettings jwt) {
    }

    /** HS256 access-token settings. The secret is never printed by {@link #toString()}. */
    public record JwtSettings(
            @NotNull String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl) {

        /** HS256 needs a key at least as long as its 256-bit output. */
        static final int MIN_SECRET_BYTES = 32;

        @AssertTrue(message = "security.jwt.secret is not configured (set the JWT_SECRET environment variable)")
        public boolean isSecretConfigured() {
            return secret == null || (!secret.isBlank() && !secret.contains("${"));
        }

        @AssertTrue(message = "security.jwt.secret must be at least 32 bytes (256 bits) for HS256")
        public boolean isSecretLongEnough() {
            return secret == null || secret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
        }

        @AssertTrue(message = "security.jwt.access-token-ttl must be positive")
        public boolean isAccessTokenTtlPositive() {
            return accessTokenTtl == null || isPositive(accessTokenTtl);
        }

        @Override
        public String toString() {
            return "JwtSettings[secret=******, issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
        }
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
