package com.example.jobprocessor.job.simulation;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Parses and validates the optional {@code payload.simulation} object:
 * <pre>{"durationMs": 90000, "failureMode": "TRANSIENT_ONCE", "failAfterMs": 1000}</pre>
 * Durations must lie within the configured simulation range. Failure modes other than NONE are
 * rejected unless failure injection is enabled, which {@code FailureInjectionGuard} restricts to
 * the dev and test profiles.
 */
@Component
public class SimulationSpecParser {

    public static final String PAYLOAD_KEY = "simulation";
    static final String DURATION = "durationMs";
    static final String FAILURE_MODE = "failureMode";
    static final String FAIL_AFTER = "failAfterMs";
    private static final Set<String> FIELDS = Set.of(DURATION, FAILURE_MODE, FAIL_AFTER);

    private final Duration minDuration;
    private final Duration maxDuration;
    private final boolean failureInjectionEnabled;

    public SimulationSpecParser(JobProcessorProperties properties) {
        this.minDuration = properties.simulation().minDuration();
        this.maxDuration = properties.simulation().maxDuration();
        this.failureInjectionEnabled = properties.simulation().failureInjectionEnabled();
    }

    public SimulationSpec parse(Map<String, Object> payload) {
        Object value = payload.get(PAYLOAD_KEY);
        if (value == null) {
            return SimulationSpec.DEFAULT;
        }
        if (!(value instanceof Map<?, ?> simulation)) {
            throw invalid("payload.simulation must be an object");
        }
        for (Object field : simulation.keySet()) {
            if (!FIELDS.contains(field)) {
                throw invalid("payload.simulation has unsupported field '" + field + "'; supported: " + FIELDS);
            }
        }

        Duration duration = optionalMillis(simulation.get(DURATION), DURATION);
        if (duration != null && (duration.compareTo(minDuration) < 0 || duration.compareTo(maxDuration) > 0)) {
            throw invalid("payload.simulation." + DURATION + " must be between " + minDuration.toMillis()
                    + " and " + maxDuration.toMillis());
        }

        FailureMode failureMode = parseFailureMode(simulation.get(FAILURE_MODE));
        if (failureMode != FailureMode.NONE && !failureInjectionEnabled) {
            throw new InvalidJobPayloadException("FAILURE_INJECTION_DISABLED",
                    "Failure injection is not available in this environment");
        }

        Duration failAfter = optionalMillis(simulation.get(FAIL_AFTER), FAIL_AFTER);
        if (failAfter != null) {
            if (failureMode == FailureMode.NONE) {
                throw invalid("payload.simulation." + FAIL_AFTER + " requires a failureMode other than NONE");
            }
            Duration limit = duration != null ? duration : maxDuration;
            if (failAfter.isNegative() || failAfter.compareTo(limit) > 0) {
                throw invalid("payload.simulation." + FAIL_AFTER + " must be between 0 and " + limit.toMillis());
            }
        }
        return new SimulationSpec(duration, failureMode, failAfter);
    }

    private static Duration optionalMillis(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Integer || value instanceof Long)) {
            throw invalid("payload.simulation." + field + " must be an integer number of milliseconds");
        }
        return Duration.ofMillis(((Number) value).longValue());
    }

    private static FailureMode parseFailureMode(Object value) {
        if (value == null) {
            return FailureMode.NONE;
        }
        if (value instanceof String name) {
            for (FailureMode mode : FailureMode.values()) {
                if (mode.name().equals(name)) {
                    return mode;
                }
            }
        }
        throw invalid("payload.simulation." + FAILURE_MODE + " must be one of " + Arrays.toString(FailureMode.values()));
    }

    private static InvalidJobPayloadException invalid(String detail) {
        return new InvalidJobPayloadException(detail);
    }
}
