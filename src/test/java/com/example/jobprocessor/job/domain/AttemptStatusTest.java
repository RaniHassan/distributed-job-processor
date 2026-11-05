package com.example.jobprocessor.job.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AttemptStatusTest {

    @ParameterizedTest
    @EnumSource(value = AttemptStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void runningAttemptCanCloseIntoEveryFinalStatus(AttemptStatus target) {
        assertThat(target.isFinal()).isTrue();
        assertThat(AttemptStatus.RUNNING.canTransitionTo(target)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = AttemptStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void finalAttemptsCannotChange(AttemptStatus finalStatus) {
        for (AttemptStatus target : AttemptStatus.values()) {
            assertThat(finalStatus.canTransitionTo(target)).as(finalStatus + " -> " + target).isFalse();
        }
    }

    @Test
    void runningCannotTransitionToRunning() {
        assertThat(AttemptStatus.RUNNING.isFinal()).isFalse();
        assertThat(AttemptStatus.RUNNING.canTransitionTo(AttemptStatus.RUNNING)).isFalse();
    }
}
