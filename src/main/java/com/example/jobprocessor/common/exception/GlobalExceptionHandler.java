package com.example.jobprocessor.common.exception;

import com.example.jobprocessor.common.web.RequestTooLargeException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception to a ProblemDetail with {@code errorCode} and {@code traceId}. Messages
 * from unexpected exceptions, stack traces and infrastructure details never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        return problem(ex.status(), ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(RequestTooLargeException.class)
    public ResponseEntity<ProblemDetail> handleRequestTooLarge(RequestTooLargeException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        log.warn("Authorization denied: user={} path={}", currentUserName(), path(request));
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions for this operation");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, WebRequest request) {
        log.warn("Authentication failed: path={} reason={}", path(request), ex.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
    }

    /** The database is unreachable or refusing connections: a temporary condition for the client. */
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        TransientDataAccessResourceException.class,
        CannotCreateTransactionException.class
    })
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        log.error("Database unavailable: {}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable, please retry later");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error on {}", path(request), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        RequestTooLargeException tooLarge = findCause(ex, RequestTooLargeException.class);
        if (tooLarge != null) {
            return asObject(handleRequestTooLarge(tooLarge));
        }
        // Parser messages can echo request content; keep the detail generic.
        return asObject(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing, malformed or contains unsupported fields"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ApiProblems.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Fills in the error code for every other standard Spring MVC exception. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(status);
        ApiProblems.decorate(problem, errorCodeFor(ex, status));
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static String errorCodeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof MissingRequestHeaderException missing) {
            return IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(missing.getHeaderName())
                    ? "IDEMPOTENCY_KEY_MISSING" : "MISSING_HEADER";
        }
        if (ex instanceof TypeMismatchException) {
            return "INVALID_PARAMETER";
        }
        if (ex instanceof HandlerMethodValidationException) {
            return "VALIDATION_FAILED";
        }
        if (ex instanceof NoResourceFoundException) {
            return "NOT_FOUND";
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return "METHOD_NOT_ALLOWED";
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return "UNSUPPORTED_MEDIA_TYPE";
        }
        return status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR";
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String errorCode, String detail) {
        return ResponseEntity.status(status).body(ApiProblems.create(status, errorCode, detail));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> response) {
        return (ResponseEntity) response;
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getMethod() + " " + servlet.getRequest().getRequestURI()
                : request.getDescription(false);
    }

    private static String currentUserName() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "anonymous" : authentication.getName();
    }
}
