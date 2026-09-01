package com.patipp.common.error;

import org.springframework.http.HttpStatus;

/** Credentials are missing, malformed, expired or simply wrong. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
