package com.example.jobprocessor.job.idempotency;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeysTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "order-42", "550e8400-e29b-41d4-a716-446655440000", "with space inside"})
    void acceptsReasonableKeys(String key) {
        assertThatCode(() -> IdempotencyKeys.validate(key)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsBlankKeys(String key) {
        assertThatThrownBy(() -> IdempotencyKeys.validate(key))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessageContaining("must not be blank");
    }

    @org.junit.jupiter.api.Test
    void acceptsMaximumLengthAndRejectsLonger() {
        assertThatCode(() -> IdempotencyKeys.validate("k".repeat(255))).doesNotThrowAnyException();
        assertThatThrownBy(() -> IdempotencyKeys.validate("k".repeat(256)))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessageContaining("at most 255");
    }

    @org.junit.jupiter.api.Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> IdempotencyKeys.validate("abc\ndef"))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessageContaining("control characters");
    }
}
