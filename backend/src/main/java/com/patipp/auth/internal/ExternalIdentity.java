package com.patipp.auth.internal;

/**
 * What a verified external assertion tells us about a person.
 *
 * <p>Provider-agnostic on purpose. Google is the first source of these, and the account
 * resolution that consumes them should not have to change when it is not the only one.
 *
 * @param subject     the provider's immutable identifier for this account, which is what
 *                    PATIPP keys on. Never the email - an address can change hands, and a
 *                    system that treats one as a name for a person can be made to hand over
 *                    the wrong account
 * @param email       the address the provider asserted and has verified
 * @param displayName the profile name the provider offered, which may be null; providers
 *                    let people withhold it
 */
public record ExternalIdentity(String subject, String email, String displayName) {
}
