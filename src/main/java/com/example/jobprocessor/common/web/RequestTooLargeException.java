package com.example.jobprocessor.common.web;

import java.io.IOException;

/**
 * Raised while reading a request body that exceeds the configured limit. An {@link IOException}
 * because it surfaces from the input stream during message conversion.
 */
public class RequestTooLargeException extends IOException {

    public RequestTooLargeException(long maxBytes) {
        super("Request body exceeds the maximum size of " + maxBytes + " bytes");
    }
}
