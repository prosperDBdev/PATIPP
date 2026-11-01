package com.patipp.auth.domain;

/**
 * An external identity provider PATIPP will accept a signed assertion from.
 *
 * <p>Stored as its name in {@code user_identities.provider}, which is a
 * {@code varchar} guarded by a CHECK constraint rather than a Postgres enum. Adding a
 * provider is therefore a one-value migration on that constraint plus a constant here -
 * not a type rewrite, and not a new column on {@code users}.
 *
 * <p>Note what is absent: a password is not a provider. A local account is the absence of
 * any row in {@code user_identities}, because a password lives on the user itself.
 */
public enum AuthProvider {
    GOOGLE
}
