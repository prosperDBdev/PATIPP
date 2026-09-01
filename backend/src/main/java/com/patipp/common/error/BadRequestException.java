package com.patipp.common.error;

import org.springframework.http.HttpStatus;

/** The request is invalid in a way bean validation cannot express. */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
