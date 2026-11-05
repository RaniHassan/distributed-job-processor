package com.example.jobprocessor.common.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start an instance that enables simulated failure injection outside the
 * {@code dev} and {@code test} profiles, so an environment variable alone cannot turn it on
 * in production.
 */
@Component
public class FailureInjectionGuard {

    static final String[] ALLOWED_PROFILES = {"dev", "test"};

    public FailureInjectionGuard(JobProcessorProperties properties, Environment environment) {
        if (properties.simulation().failureInjectionEnabled() && !environment.matchesProfiles(ALLOWED_PROFILES)) {
            throw new IllegalStateException(
                    "jobprocessor.simulation.failure-injection-enabled=true is only allowed with the dev or test profile");
        }
    }
}
