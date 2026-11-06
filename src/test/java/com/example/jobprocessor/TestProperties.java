package com.example.jobprocessor;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.common.config.JobProcessorProperties.Api;
import com.example.jobprocessor.common.config.JobProcessorProperties.Dispatcher;
import com.example.jobprocessor.common.config.JobProcessorProperties.Idempotency;
import com.example.jobprocessor.common.config.JobProcessorProperties.JwtSettings;
import com.example.jobprocessor.common.config.JobProcessorProperties.Lease;
import com.example.jobprocessor.common.config.JobProcessorProperties.Metrics;
import com.example.jobprocessor.common.config.JobProcessorProperties.Recovery;
import com.example.jobprocessor.common.config.JobProcessorProperties.Retry;
import com.example.jobprocessor.common.config.JobProcessorProperties.Security;
import com.example.jobprocessor.common.config.JobProcessorProperties.Shutdown;
import com.example.jobprocessor.common.config.JobProcessorProperties.Simulation;
import com.example.jobprocessor.common.config.JobProcessorProperties.Worker;
import java.time.Duration;
import java.util.List;
import org.springframework.util.unit.DataSize;

/** Properties with the README §20 defaults, for unit tests that do not start Spring. */
public final class TestProperties {

    public static final String JWT_SECRET = "unit-test-signing-secret-0123456789-abcdef";
    public static final String JWT_ISSUER = "test-issuer";

    private TestProperties() {
    }

    public static JobProcessorProperties create(boolean failureInjectionEnabled) {
        return new JobProcessorProperties(
                new Worker(5, Duration.ofMillis(500), "unit-test"),
                new Dispatcher(true, Duration.ofSeconds(1)),
                new Lease(Duration.ofSeconds(30), Duration.ofSeconds(10)),
                new Recovery(true, Duration.ofSeconds(5)),
                new Retry(3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20)), 0.2),
                new Simulation(Duration.ofMinutes(1), Duration.ofMinutes(5), failureInjectionEnabled),
                new Shutdown(Duration.ofSeconds(30)),
                new Idempotency(Duration.ofHours(24), Duration.ofHours(1)),
                new Api(DataSize.ofKilobytes(64), 32),
                new Security(new JwtSettings(JWT_SECRET, JWT_ISSUER, Duration.ofMinutes(15))),
                new Metrics(Duration.ofSeconds(15)));
    }
}
