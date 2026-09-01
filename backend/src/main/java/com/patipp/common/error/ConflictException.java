package com.patipp.common.error;

import org.springframework.http.HttpStatus;

/** The request is well formed but conflicts with existing state, e.g. a duplicate name. */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
