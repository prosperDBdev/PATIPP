package com.patipp.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds the one problem+json shape every error in this application uses.
 *
 * <p>Published so a feature module can map its own exceptions without either duplicating the
 * shape or forcing {@code common} to import that module. The alternative - teaching the
 * global handler about {@code questions}, then {@code sessions}, then every module in turn -
 * would invert the dependency at the exact point everything else relies on, which is what the
 * "common depends on nothing" rule exists to prevent.
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    /**
     * @param code stable machine-readable identifier the frontend can branch on, such as
     *             {@code question.content_invalid}
     */
    public static ProblemDetail of(HttpStatus status, String detail, String code,
                                   HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.currentCorrelationId());
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
