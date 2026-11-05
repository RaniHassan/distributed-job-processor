package com.example.jobprocessor.job.idempotency;

import com.example.jobprocessor.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidIdempotencyKeyException extends ApiException {

    public InvalidIdempotencyKeyException(String detail) {
        super(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", detail);
    }
}
