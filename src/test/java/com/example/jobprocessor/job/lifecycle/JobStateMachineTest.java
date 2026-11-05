package com.example.jobprocessor.job.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.job.domain.JobStatus;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class JobStateMachineTest {

    /** README §9 "Valid transitions", verbatim. */
    private static final Set<String> SPECIFIED = Set.of(
            "PENDING->RUNNING",
            "PENDING->CANCELLED",
            "RUNNING->COMPLETED",
            "RUNNING->FAILED",
            "RUNNING->CANCEL_REQUESTED",
            "CANCEL_REQUESTED->CANCELLED",
            "RUNNING->PENDING");

    static Stream<Arguments> allPairs() {
        return Arrays.stream(JobStatus.values())
                .flatMap(from -> Arrays.stream(JobStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void allowsExactlyTheSpecifiedTransitions(JobStatus from, JobStatus to) {
        boolean expected = SPECIFIED.contains(from + "->" + to);

        assertThat(JobStateMachine.canTransition(from, to)).isEqualTo(expected);
        if (expected) {
            assertThatCode(() -> JobStateMachine.requireTransition(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> JobStateMachine.requireTransition(from, to))
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessage("Illegal job state transition: " + from + " -> " + to)
                    .satisfies(e -> {
                        IllegalStateTransitionException ex = (IllegalStateTransitionException) e;
                        assertThat(ex.from()).isEqualTo(from);
                        assertThat(ex.to()).isEqualTo(to);
                    });
        }
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalStatesHaveNoOutgoingTransitions(JobStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(JobStateMachine.allowedTargets(terminal)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"PENDING", "RUNNING", "CANCEL_REQUESTED"})
    void nonTerminalStatesCanProgress(JobStatus status) {
        assertThat(status.isTerminal()).isFalse();
        assertThat(JobStateMachine.allowedTargets(status)).isNotEmpty();
    }

    @Test
    void noStateTransitionsToItself() {
        for (JobStatus status : JobStatus.values()) {
            assertThat(JobStateMachine.canTransition(status, status)).as(status.name()).isFalse();
        }
    }

    @Test
    void cancellationCannotBeUndoneOrTurnedIntoCompletion() {
        assertThat(JobStateMachine.allowedTargets(JobStatus.CANCEL_REQUESTED)).containsExactly(JobStatus.CANCELLED);
    }

    @Test
    void ownedStatesMatchTheDatabaseOwnershipConstraint() {
        assertThat(Arrays.stream(JobStatus.values()).filter(JobStatus::isOwned))
                .containsExactlyInAnyOrder(JobStatus.RUNNING, JobStatus.CANCEL_REQUESTED);
    }

    @Test
    void allowedTargetsCannotBeModified() {
        Set<JobStatus> targets = JobStateMachine.allowedTargets(JobStatus.PENDING);

        assertThatThrownBy(() -> targets.add(JobStatus.COMPLETED)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(JobStateMachine.canTransition(JobStatus.PENDING, JobStatus.COMPLETED)).isFalse();
    }
}
