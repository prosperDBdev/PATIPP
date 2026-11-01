package com.patipp.auth.internal;

import com.patipp.auth.domain.AuthProvider;
import com.patipp.auth.domain.User;
import com.patipp.auth.domain.UserIdentity;
import com.patipp.auth.domain.UserIdentityRepository;
import com.patipp.auth.domain.UserRepository;
import com.patipp.common.error.UnauthorizedException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps a verified external identity onto a PATIPP user, creating or linking as required.
 *
 * <p><strong>This class assumes its input has already been verified.</strong> It does no
 * cryptography and asks no questions about where the identity came from; it acts on it.
 * In particular, the linking branch below is only safe because
 * {@link GoogleIdTokenVerifier} has already refused any assertion whose email address the
 * provider had not verified. Calling this with an unverified identity would turn it into a
 * way to be handed somebody else's account.
 *
 * <p>Provider-agnostic, so adding GitHub later is a new verifier and one more value in the
 * {@code user_identities_provider_known} constraint, not a second copy of this logic.
 */
@Component
class ProviderAccountResolver {

    private static final Logger log = LoggerFactory.getLogger(ProviderAccountResolver.class);

    private final UserRepository users;
    private final UserIdentityRepository identities;

    ProviderAccountResolver(UserRepository users, UserIdentityRepository identities) {
        this.users = users;
        this.identities = identities;
    }

    /**
     * @param requestedTimezone the browser's IANA zone, applied only when this call creates
     *                          the account. An existing learner's own setting is never
     *                          overwritten by the machine they happened to sign in from.
     */
    User resolve(AuthProvider provider,
                 ExternalIdentity identity,
                 String requestedTimezone,
                 Instant now) {
        Optional<UserIdentity> known =
                identities.findByProviderAndExternalId(provider, identity.subject());

        if (known.isPresent()) {
            UserIdentity link = known.get();
            // Follow an address change at the provider. The stored email is a copy for
            // auditing; the account was found by subject, so a changed address moves
            // nothing and cannot be used to reach a different user.
            link.recordLogin(identity.email(), now);
            return users.findById(link.userId())
                    .orElseThrow(() -> new UnauthorizedException(
                            "auth.identity_orphaned",
                            "That sign-in is no longer valid. Please sign in again."));
        }

        String email = User.normaliseEmail(identity.email());
        Optional<User> sameAddress = users.findByEmail(email);

        if (sameAddress.isPresent()) {
            // Someone who registered with a password is now signing in with Google using the
            // same address. Linking is right: it is one person, and refusing would strand
            // them beside their own study history with no way to reach it.
            //
            // The safety rests entirely on the provider having verified the address, since
            // holding the mailbox is what proves the two are the same person. The residual
            // risk is inherent to federated sign-in rather than to this code: whoever
            // administers the email domain can always create an account that claims an
            // address in it.
            User user = sameAddress.get();
            identities.save(UserIdentity.link(user.id(), provider, identity.subject(), email, now));
            log.info("Linked {} identity to existing user {}", provider, user.id());
            return user;
        }

        // saveAndFlush rather than save: user_identities.user_id is a plain UUID column
        // rather than a JPA association, so Hibernate does not know one row depends on the
        // other and hibernate.order_inserts is free to write them in either order. Flushing
        // the user first makes the ordering explicit instead of incidental. It costs one
        // extra round trip on the once-per-learner path.
        User created = users.saveAndFlush(
                User.fromProvider(email, identity.displayName(), safeTimezone(requestedTimezone)));
        identities.save(UserIdentity.link(created.id(), provider, identity.subject(), email, now));
        log.info("Created user {} from a {} sign-in", created.id(), provider);
        return created;
    }

    /**
     * The timezone arrives from the browser, so it is an untrusted string on its way into a
     * column with a length constraint and a meaning. Anything the JVM does not recognise as
     * a zone becomes UTC rather than a 500 at the very end of an otherwise valid sign-in.
     */
    private static String safeTimezone(String requested) {
        if (requested == null || requested.isBlank()) {
            return "UTC";
        }
        String candidate = requested.strip();
        try {
            return ZoneId.of(candidate).getId();
        } catch (DateTimeException notAZone) {
            log.warn("Ignoring an unrecognised timezone from a sign-in: {}", candidate);
            return "UTC";
        }
    }
}
