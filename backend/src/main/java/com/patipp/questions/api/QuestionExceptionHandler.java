package com.patipp.questions.api;

import com.patipp.common.web.ProblemDetails;
import com.patipp.questions.domain.content.ContentValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the questions module's own domain failures to HTTP.
 *
 * <p>Lives here rather than in the global handler so that {@code common} does not have to
 * know this module exists. Each module owning its own translation is what keeps the
 * dependency arrow pointing one way.
 *
 * <p>The response uses the same {@code errors} array as bean validation, so the frontend has
 * a single code path for "some fields are wrong" whether the offending field was a missing
 * display name or a multiple-choice question with two correct answers.
 *
 * <p>Explicitly ordered ahead of the global handler. An unannotated advice defaults to
 * lowest precedence, and so does the global one, so without this the tie was broken by bean
 * name - and "GlobalExceptionHandler" sorts first, matched everything through its
 * Exception handler, and returned 500 instead of this 400.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class QuestionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(QuestionExceptionHandler.class);

    @ExceptionHandler(ContentValidationException.class)
    public ProblemDetail handleContentValidation(ContentValidationException exception,
                                                 HttpServletRequest request) {
        List<Map<String, String>> errors = exception.errors().stream()
                .map(error -> Map.of("field", error.field(), "message", error.message()))
                .toList();

        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST,
                "The question content is not valid for its type.",
                "question.content_invalid", request);
        problem.setProperty("errors", errors);

        log.warn("{} {} -> 400 invalid question content ({} problem(s))",
                request.getMethod(), request.getRequestURI(), errors.size());
        return problem;
    }
}
