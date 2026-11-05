package com.example.jobprocessor.job.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.TestProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Configured range in {@link TestProperties}: 1 to 5 minutes. */
class SimulationSpecParserTest {

    private final SimulationSpecParser devParser = new SimulationSpecParser(TestProperties.create(true));
    private final SimulationSpecParser prodParser = new SimulationSpecParser(TestProperties.create(false));

    @Test
    void payloadWithoutSimulationUsesDefaults() {
        assertThat(prodParser.parse(Map.of("someParameter", "someValue"))).isEqualTo(SimulationSpec.DEFAULT);
    }

    @Test
    void parsesFullSpecification() {
        SimulationSpec spec = devParser.parse(simulation(Map.of(
                "durationMs", 90_000, "failureMode", "TRANSIENT_ONCE", "failAfterMs", 1_000)));

        assertThat(spec.duration()).isEqualTo(Duration.ofSeconds(90));
        assertThat(spec.failureMode()).isEqualTo(FailureMode.TRANSIENT_ONCE);
        assertThat(spec.failAfter()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void durationControlIsAvailableWithoutFailureInjection() {
        assertThat(prodParser.parse(simulation(Map.of("durationMs", 60_000))).duration())
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void acceptsDurationBoundsInclusive() {
        assertThat(devParser.parse(simulation(Map.of("durationMs", 60_000))).duration()).isEqualTo(Duration.ofMinutes(1));
        assertThat(devParser.parse(simulation(Map.of("durationMs", 300_000L))).duration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsDurationBelowMinimum() {
        assertInvalid(simulation(Map.of("durationMs", 59_999)), "between 60000 and 300000");
    }

    @Test
    void rejectsDurationAboveMaximum() {
        assertInvalid(simulation(Map.of("durationMs", 300_001)), "between 60000 and 300000");
    }

    @Test
    void rejectsNonIntegerDuration() {
        assertInvalid(simulation(Map.of("durationMs", 90_000.5)), "integer number of milliseconds");
        assertInvalid(simulation(Map.of("durationMs", "90000")), "integer number of milliseconds");
    }

    @ParameterizedTest
    @EnumSource(value = FailureMode.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void failureInjectionIsRejectedWhenDisabled(FailureMode mode) {
        assertThatThrownBy(() -> prodParser.parse(simulation(Map.of("failureMode", mode.name()))))
                .isInstanceOf(InvalidJobPayloadException.class)
                .satisfies(e -> assertThat(((InvalidJobPayloadException) e).errorCode())
                        .isEqualTo("FAILURE_INJECTION_DISABLED"));
    }

    @Test
    void failureModeNoneIsAllowedWhenInjectionDisabled() {
        assertThat(prodParser.parse(simulation(Map.of("failureMode", "NONE"))).failureMode()).isEqualTo(FailureMode.NONE);
    }

    @ParameterizedTest
    @EnumSource(FailureMode.class)
    void everyFailureModeIsAcceptedWhenEnabled(FailureMode mode) {
        assertThat(devParser.parse(simulation(Map.of("failureMode", mode.name()))).failureMode()).isEqualTo(mode);
    }

    @Test
    void rejectsUnknownFailureMode() {
        assertInvalid(simulation(Map.of("failureMode", "EXPLODE")), "must be one of");
        assertInvalid(simulation(Map.of("failureMode", "transient_once")), "must be one of");
    }

    @Test
    void rejectsUnknownFields() {
        assertInvalid(simulation(Map.of("durationSeconds", 90)), "unsupported field 'durationSeconds'");
    }

    @Test
    void rejectsNonObjectSimulation() {
        assertInvalid(Map.of("simulation", "fast"), "must be an object");
    }

    @Test
    void failAfterRequiresAFailureMode() {
        assertInvalid(simulation(Map.of("failAfterMs", 10)), "requires a failureMode");
    }

    @Test
    void failAfterMustFitInsideTheDuration() {
        assertInvalid(simulation(Map.of("durationMs", 60_000, "failureMode", "PERMANENT", "failAfterMs", 60_001)),
                "between 0 and 60000");
        assertInvalid(simulation(Map.of("failureMode", "PERMANENT", "failAfterMs", -1)), "between 0 and 300000");
    }

    private void assertInvalid(Map<String, Object> payload, String message) {
        assertThatThrownBy(() -> devParser.parse(payload))
                .isInstanceOf(InvalidJobPayloadException.class)
                .hasMessageContaining(message)
                .satisfies(e -> assertThat(((InvalidJobPayloadException) e).errorCode()).isEqualTo("INVALID_PAYLOAD"));
    }

    private static Map<String, Object> simulation(Map<String, Object> simulation) {
        return Map.of("simulation", simulation);
    }
}
