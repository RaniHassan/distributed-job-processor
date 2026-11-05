package com.example.jobprocessor.security.service;

import com.example.jobprocessor.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Deliberately identical for unknown users, wrong passwords and disabled accounts. */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
    }
}
