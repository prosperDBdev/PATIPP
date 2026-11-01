package com.patipp.common.error;

import org.springframework.http.HttpStatus;

/**
 * A feature the caller asked for is not available on this deployment.
 *
 * <p>Distinct from a 401 on purpose. A client that is told "your token is invalid" will
 * retry, ask the user to sign in again, and eventually blame itself; one that is told the
 * server cannot do this at all can say so and offer another route. Returning 401 for a
 * missing server-side configuration sends whoever debugs it looking in the wrong place.
 */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(String code, String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
