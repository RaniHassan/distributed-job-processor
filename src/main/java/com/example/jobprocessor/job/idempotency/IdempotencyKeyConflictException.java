package com.example.jobprocessor.job.idempotency;

import com.example.jobprocessor.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class IdempotencyKeyConflictException extends ApiException {

    public IdempotencyKeyConflictException() {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency key was already used with a different request");
    }
}
