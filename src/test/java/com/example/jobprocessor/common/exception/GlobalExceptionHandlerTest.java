package com.example.jobprocessor.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.common.web.TraceIdFilter;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.ServletWebRequest;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void databaseConnectionFailureIs503WithoutInternals() {
        ResponseEntity<ProblemDetail> response = handler.handleDatabaseUnavailable(
                new CannotGetJdbcConnectionException("Connection to db:5432 refused", new SQLException("refused")));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "SERVICE_UNAVAILABLE");
        assertThat(response.getBody().getDetail()).doesNotContain("5432", "refused");
    }

    @Test
    void transactionStartFailureIs503() {
        ResponseEntity<ProblemDetail> response =
                handler.handleDatabaseUnavailable(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void unexpectedErrorsAreGeneric500() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-1");

        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(
                new IllegalStateException("password=hunter2 in internal state"),
                new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/jobs")));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().getProperties())
                .containsEntry("errorCode", "INTERNAL_ERROR")
                .containsEntry("traceId", "trace-1");
    }
}
