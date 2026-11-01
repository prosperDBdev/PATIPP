package com.patipp.auth.internal;

import com.patipp.common.error.ServiceUnavailableException;
import com.patipp.common.error.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Turns a Google ID token into an identity PATIPP is willing to act on.
 *
 * <p>This is the trust boundary. Everything downstream - creating accounts, linking them,
 * issuing sessions - assumes the assertion that got here was genuinely signed by Google,
 * addressed to this application, and still valid. If that assumption is wrong anywhere in
 * this class, an attacker can mint any identity they like by presenting a token they wrote
 * themselves.
 *
 * <p>The cryptographic half is delegated to the decoder built in {@link GoogleIdTokenConfig},
 * which checks the signature against Google's published keys and enforces expiry, issuer and
 * audience. What is left here is the part that is easy to get wrong by being helpful: the
 * claims themselves.
 */
@Component
public class GoogleIdTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);

    private final JwtDecoder decoder;
    private final GoogleAuthProperties properties;

    public GoogleIdTokenVerifier(@Qualifier("googleIdTokenDecoder") JwtDecoder decoder,
                                 GoogleAuthProperties properties) {
        this.decoder = decoder;
        this.properties = properties;
    }

    /**
     * @throws ServiceUnavailableException if Google sign-in is not configured here
     * @throws UnauthorizedException       if the token is not a currently valid assertion
     *                                     from Google about a verified address
     */
    public ExternalIdentity verify(String rawIdToken) {
        if (!properties.isConfigured()) {
            throw new ServiceUnavailableException(
                    "auth.google_not_configured",
                    "Google sign-in is not available on this server.");
        }
        if (rawIdToken == null || rawIdToken.isBlank()) {
            throw invalidToken();
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(rawIdToken);
        } catch (JwtException failed) {
            // Deliberately not echoed to the client. The reason a token failed - expired,
            // wrong audience, bad signature - is useful to whoever is probing and useless
            // to a legitimate user, whose only available action is to try again.
            log.warn("Rejected a Google ID token: {}", failed.getMessage());
            throw invalidToken();
        }

        return toIdentity(jwt);
    }

    /**
     * The claim rules, separated so they can be tested without a network or a real token.
     *
     * <p>Package-private rather than public: these rules are meaningless applied to a JWT
     * that has not already been verified, and a public method here would be an invitation
     * to call them on one that has not.
     */
    static ExternalIdentity toIdentity(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new UnauthorizedException(
                    "auth.google_token_invalid", "That Google sign-in could not be verified.");
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            // Only reachable if the token was minted without the email scope. Creating an
            // account would mean inventing an address, and every account here is reached by
            // one, so this fails instead.
            throw new UnauthorizedException(
                    "auth.google_email_missing",
                    "Google did not share an email address, which PATIPP needs to create your account.");
        }

        if (!isVerified(jwt.getClaim("email_verified"))) {
            // The single most important check in this class. PATIPP links a Google account
            // to an existing PATIPP account when the addresses match, so an unverified
            // address is a request to be handed somebody else's data: anyone able to claim
            // an address at an identity provider could claim yours.
            throw new UnauthorizedException(
                    "auth.google_email_unverified",
                    "That Google account's email address is not verified.");
        }

        return new ExternalIdentity(subject, email, jwt.getClaimAsString("name"));
    }

    /**
     * Google sends {@code email_verified} as a JSON boolean, but the claim has historically
     * been serialised as the string "true" by some issuers and intermediaries. Both are
     * accepted; everything else, including null, counts as not verified.
     */
    private static boolean isVerified(Object claim) {
        if (claim instanceof Boolean verified) {
            return verified;
        }
        return claim instanceof String text && "true".equalsIgnoreCase(text);
    }

    /**
     * One message for every way a token can be unacceptable. The specific reason is logged
     * at the point it is known and never returned: it is of use only to whoever is probing.
     */
    private static UnauthorizedException invalidToken() {
        return new UnauthorizedException(
                "auth.google_token_invalid", "That Google sign-in could not be verified.");
    }
}
