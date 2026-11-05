package com.example.jobprocessor.job.simulation;

import com.example.jobprocessor.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidJobPayloadException extends ApiException {

    public InvalidJobPayloadException(String detail) {
        this("INVALID_PAYLOAD", detail);
    }

    public InvalidJobPayloadException(String errorCode, String detail) {
        super(HttpStatus.BAD_REQUEST, errorCode, detail);
    }
}
