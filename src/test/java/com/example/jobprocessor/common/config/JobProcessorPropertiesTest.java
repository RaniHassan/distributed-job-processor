package com.example.jobprocessor.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobProcessorPropertiesTest {

    /** Loads src/main/resources/application.yml, i.e. the production defaults. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(JobProcessorConfiguration.class, FailureInjectionGuard.class);

    @Test
    void defaultsMatchSpecification() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            JobProcessorProperties p = context.getBean(JobProcessorProperties.class);

            assertThat(p.worker().threads()).isEqualTo(5);
            assertThat(p.worker().cancellationCheckInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(p.dispatcher().pollInterval()).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
            assertThat(p.lease().duration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.lease().heartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.retry().maxRetries()).isEqualTo(3);
            assertThat(p.retry().backoff())
                    .containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));
            assertThat(p.simulation().minDuration()).isEqualTo(Duration.ofMinutes(1));
            assertThat(p.simulation().maxDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(p.simulation().failureInjectionEnabled()).isFalse();
            assertThat(p.shutdown().timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.idempotency().retention()).isEqualTo(Duration.ofHours(24));
        });
    }

    @Test
    void rejectsLeaseNotLongerThanTwoHeartbeats() {
        assertBindingFails("lease.duration must be longer than twice lease.heartbeat-interval",
                "jobprocessor.lease.duration=20s", "jobprocessor.lease.heartbeat-interval=10s");
    }

    @Test
    void rejectsCancellationCheckNotFasterThanHeartbeat() {
        assertBindingFails("worker.cancellation-check-interval must be shorter than lease.heartbeat-interval",
                "jobprocessor.worker.cancellation-check-interval=10s");
    }

    @Test
    void rejectsZeroWorkerThreads() {
        assertBindingFails("threads", "jobprocessor.worker.threads=0");
    }

    @Test
    void rejectsNegativeMaxRetries() {
        assertBindingFails("maxRetries", "jobprocessor.retry.max-retries=-1");
    }

    @Test
    void rejectsJitterAboveOne() {
        assertBindingFails("jitterFactor", "jobprocessor.retry.jitter-factor=1.5");
    }

    @Test
    void rejectsNegativeBackoff() {
        assertBindingFails("backoff delays must not be negative", "jobprocessor.retry.backoff=5s,-1s");
    }

    @Test
    void rejectsSimulationMinLongerThanMax() {
        assertBindingFails("simulation.min-duration must be positive",
                "jobprocessor.simulation.min-duration=6m", "jobprocessor.simulation.max-duration=5m");
    }

    @Test
    void rejectsNonPositiveDispatcherPollInterval() {
        assertBindingFails("must be positive", "jobprocessor.dispatcher.poll-interval=0s");
    }

    @Test
    void acceptsCustomCompatibleTimings() {
        runner.withPropertyValues(
                        "jobprocessor.lease.duration=5s",
                        "jobprocessor.lease.heartbeat-interval=2s",
                        "jobprocessor.worker.cancellation-check-interval=200ms",
                        "jobprocessor.retry.backoff=1s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JobProcessorProperties.class).retry().backoff())
                            .isEqualTo(List.of(Duration.ofSeconds(1)));
                });
    }

    @Test
    void failureInjectionIsRejectedWithoutDevOrTestProfile() {
        runner.withPropertyValues("jobprocessor.simulation.failure-injection-enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("only allowed with the dev or test profile");
                });
    }

    @Test
    void failureInjectionIsRejectedWithNoActiveProfile() {
        runner.withPropertyValues("jobprocessor.simulation.failure-injection-enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failureInjectionIsAllowedWithDevOrTestProfile() {
        for (String profile : FailureInjectionGuard.ALLOWED_PROFILES) {
            runner.withPropertyValues("jobprocessor.simulation.failure-injection-enabled=true")
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                    .run(context -> assertThat(context).as(profile).hasNotFailed());
        }
    }

    private void assertBindingFails(String expectedMessage, String... properties) {
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining(expectedMessage);
        });
    }
}
