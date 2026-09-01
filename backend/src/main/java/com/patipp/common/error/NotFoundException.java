package com.patipp.common.error;

import org.springframework.http.HttpStatus;

/**
 * The resource does not exist, or exists but belongs to another user.
 *
 * <p>Both cases deliberately produce 404 rather than 403: a 403 would confirm that
 * the id is real, which lets an attacker enumerate other users' resources.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
