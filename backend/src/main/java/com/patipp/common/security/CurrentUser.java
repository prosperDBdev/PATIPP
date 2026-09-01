package com.patipp.common.security;

import com.patipp.common.error.UnauthorizedException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Reads the authenticated caller out of the security context.
 *
 * <p>Services take this rather than accepting a user id as a method parameter. A user id
 * that arrives as an argument can be supplied by a caller, and one day some controller
 * will pass the wrong one; a user id read from the verified token cannot be.
 */
@Component
public class CurrentUser {

    /** @throws UnauthorizedException when there is no authenticated caller. */
    public AuthenticatedUser require() {
        return find().orElseThrow(() ->
                new UnauthorizedException("auth.required", "Authentication is required."));
    }

    public UUID requireId() {
        return require().id();
    }

    public Optional<AuthenticatedUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}
