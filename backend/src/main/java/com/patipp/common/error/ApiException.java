package com.patipp.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for errors that are safe to surface to a client.
 *
 * <p>Anything not derived from this is treated as an internal fault: it is logged
 * with its stack trace and returned as a bare 500, so implementation details never
 * reach the wire.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    /** Stable machine-readable identifier, e.g. {@code space.not_found}. */
    public String code() {
        return code;
    }
}
