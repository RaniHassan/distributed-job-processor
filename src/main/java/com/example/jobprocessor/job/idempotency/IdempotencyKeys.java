package com.example.jobprocessor.job.idempotency;

/** Validation of the client-supplied {@code Idempotency-Key} header value. */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final int MAX_LENGTH = 255;

    private IdempotencyKeys() {
    }

    public static void validate(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must not be blank");
        }
        if (key.length() > MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must be at most " + MAX_LENGTH + " characters");
        }
        if (key.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must not contain control characters");
        }
    }
}
