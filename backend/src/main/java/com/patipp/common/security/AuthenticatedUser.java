package com.patipp.common.security;

import java.util.UUID;

/**
 * The authenticated caller, as carried in the Spring Security context.
 *
 * <p>This lives in {@code common} rather than {@code auth} on purpose: every module needs
 * to know who is calling, but no module should need to depend on how authentication is
 * implemented. {@code preparations} asking {@code auth} for a principal type would couple
 * a feature module to a mechanism it has no business knowing about.
 */
public record AuthenticatedUser(UUID id, String email) {

    public AuthenticatedUser {
        if (id == null) {
            throw new IllegalArgumentException("authenticated user id must not be null");
        }
    }
}
