package com.example.jobprocessor.security.config;

import com.example.jobprocessor.common.exception.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 403 for authenticated callers without the required permission, raised by the filter chain. */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAccessDeniedHandler.class);

    private final BearerTokenAccessDeniedHandler bearerHeaders = new BearerTokenAccessDeniedHandler();
    private final ProblemResponseWriter problemWriter;

    public ProblemAccessDeniedHandler(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        log.warn("Authorization denied: user={} {} {}",
                request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName(),
                request.getMethod(), request.getRequestURI());
        bearerHeaders.handle(request, response, ex);
        problemWriter.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions for this operation");
    }
}
