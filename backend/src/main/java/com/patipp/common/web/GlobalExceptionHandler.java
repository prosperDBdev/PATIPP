package com.patipp.common.web;

import com.patipp.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception to an RFC 7807 {@code application/problem+json} response.
 *
 * <p>Two rules hold throughout. Expected failures ({@link ApiException} and validation)
 * carry a stable {@code code} the frontend can branch on, and are logged at WARN without
 * a stack trace. Everything else is an internal fault: it is logged at ERROR with its
 * stack trace and returned as a bare 500 carrying only the correlation id, so no
 * implementation detail ever reaches the client.
 *
 * <p><b>Ordered last on purpose.</b> Spring resolves an exception by walking advice beans in
 * order and taking the best match within the first one that matches at all. This class
 * handles {@link Exception}, so without an explicit order it can be consulted first, match
 * everything, and turn a module's carefully typed domain failure into a bare 500 - which is
 * exactly what happened before this annotation existed. Lowest precedence means
 * module-specific advices always get first refusal.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI ERROR_TYPE = URI.create("about:blank");

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException exception, HttpServletRequest request) {
        log.warn("{} {} -> {} ({})", request.getMethod(), request.getRequestURI(),
                exception.status().value(), exception.code());
        return problem(exception.status(), exception.getMessage(), exception.code(), request);
    }

    /** Bean validation failure on a request body. Returns every field error at once. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException exception,
                                              HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", fieldError.getDefaultMessage() == null
                                ? "is invalid" : fieldError.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "One or more fields are invalid.", "validation.failed", request);
        problem.setProperty("errors", errors);
        log.warn("{} {} -> 400 validation ({} field errors)",
                request.getMethod(), request.getRequestURI(), errors.size());
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException exception,
                                                   HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", violation.getPropertyPath().toString(),
                        "message", violation.getMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "One or more parameters are invalid.", "validation.failed", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception,
                                              HttpServletRequest request) {
        // The parser message can echo back payload fragments, so it is logged but not returned.
        log.warn("{} {} -> 400 unreadable body: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());
        return problem(HttpStatus.BAD_REQUEST,
                "Request body is missing or not valid JSON.", "request.unreadable", request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleBadParameter(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST,
                "A required parameter is missing or of the wrong type.",
                "request.parameter_invalid", request);
    }

    /**
     * A unique or foreign-key constraint fired. This is a genuine race (two concurrent
     * creates of the same name) because services check first; the database is the
     * authority, so the check is a nicety and this is the guarantee.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException exception,
                                             HttpServletRequest request) {
        log.warn("{} {} -> 409 integrity violation: {}",
                request.getMethod(), request.getRequestURI(), rootMessage(exception));
        return problem(HttpStatus.CONFLICT,
                "That change conflicts with existing data.", "data.conflict", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException exception,
                                                  HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED,
                "That method is not supported on this endpoint.", "request.method_not_allowed", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException exception,
                                          HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "No such endpoint.", "request.no_route", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.currentCorrelationId();
        log.error("{} {} -> 500 unexpected [correlationId={}]",
                request.getMethod(), request.getRequestURI(), correlationId, exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Quote the correlation id if you report this.",
                "internal.error", request);
    }

    private ProblemDetail problem(HttpStatus status, String detail, String code,
                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ERROR_TYPE);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.currentCorrelationId());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /** Kept for symmetry with future handlers that need ordered extra properties. */
    static Map<String, Object> properties() {
        return new LinkedHashMap<>();
    }
}
