package com.example.jobprocessor.common.exception;

import com.example.jobprocessor.common.web.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the API's error body: RFC 9457 ProblemDetail plus {@code errorCode} and {@code traceId}. */
public final class ApiProblems {

    public static final String ERROR_CODE = "errorCode";
    public static final String TRACE_ID = "traceId";

    private ApiProblems() {
    }

    public static ProblemDetail create(HttpStatusCode status, String errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return decorate(problem, errorCode);
    }

    /** Adds the error code and the current request's trace id to an existing problem. */
    public static ProblemDetail decorate(ProblemDetail problem, String errorCode) {
        problem.setProperty(ERROR_CODE, errorCode);
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            problem.setProperty(TRACE_ID, traceId);
        }
        return problem;
    }
}
