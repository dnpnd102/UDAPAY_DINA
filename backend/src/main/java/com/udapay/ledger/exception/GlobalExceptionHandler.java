package com.udapay.ledger.exception;

import com.udapay.ledger.filter.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts every error into an RFC 7807 {@code application/problem+json} body.
 * Internal details (stack traces, SQL, class names) are never exposed; the
 * {@code traceId} is included so support staff can find the matching log lines.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://udapay.example/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldError)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-error",
                "Validation failed", "One or more request fields are invalid");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MissingServletRequestParameterException.class})
    public ProblemDetail handleParameterValidation(Exception ex) {
        String detail = switch (ex) {
            case ConstraintViolationException cve -> cve.getConstraintViolations().stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .reduce((a, b) -> a + "; " + b).orElse("Invalid request parameter");
            case HandlerMethodValidationException hmve -> hmve.getParameterValidationResults().stream()
                    .flatMap(r -> r.getResolvableErrors().stream())
                    .map(e -> String.valueOf(e.getDefaultMessage()))
                    .sorted()
                    .reduce((a, b) -> a + "; " + b).orElse("Invalid request parameter");
            default -> ex.getMessage();
        };
        return problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed", detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request body",
                "Request body could not be parsed");
    }

    /** Method-security denials ({@code @PreAuthorize}) → 403, never 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "You do not have the role required for this operation");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                "A valid bearer token is required");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return problem(status, status.name().toLowerCase().replace('_', '-'),
                status.getReasonPhrase(), ex.getReason() == null ? status.getReasonPhrase() : ex.getReason());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred. Quote the traceId when contacting support.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        problem.setProperty("timestamp", Instant.now().toString());
        String traceId = MDC.get(CorrelationIdFilter.TRACE_ID);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    private static Map<String, String> fieldError(FieldError fe) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", fe.getField());
        m.put("message", fe.getDefaultMessage());
        return m;
    }
}
