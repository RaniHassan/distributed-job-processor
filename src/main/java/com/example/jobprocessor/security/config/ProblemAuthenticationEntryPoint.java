package com.example.jobprocessor.security.config;

import com.example.jobprocessor.common.exception.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 401 for missing or invalid tokens: the standard WWW-Authenticate header plus a problem body. */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemAuthenticationEntryPoint.class);

    private final BearerTokenAuthenticationEntryPoint bearerHeaders = new BearerTokenAuthenticationEntryPoint();
    private final ProblemResponseWriter problemWriter;

    public ProblemAuthenticationEntryPoint(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        log.warn("Authentication failed: {} {} reason={}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        bearerHeaders.commence(request, response, ex);
        problemWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing, invalid or expired access token");
    }
}
