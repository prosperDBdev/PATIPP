package com.patipp.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.patipp.common.error.ServiceUnavailableException;
import com.patipp.common.error.UnauthorizedException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The claim rules applied to an already-verified Google token.
 *
 * <p>No Spring and no network: the decoder is a lambda, so these run in milliseconds. That
 * matters more here than almost anywhere else in the codebase - this is the code that
 * decides who someone is, and rules that are expensive to check are rules that quietly stop
 * being checked.
 *
 * <p>What is deliberately <em>not</em> tested here is the cryptography. The signature,
 * expiry, issuer and audience are enforced by the decoder built in
 * {@code GoogleIdTokenConfig}, and re-implementing that here with a hand-rolled key pair
 * would test this test's idea of Nimbus rather than Nimbus.
 */
class GoogleIdTokenVerifierTest {

    private static final GoogleAuthProperties CONFIGURED =
            new GoogleAuthProperties("test-client.apps.googleusercontent.com");

    @Test
    @DisplayName("a well-formed token yields the subject, email and name")
    void wellFormedTokenYieldsIdentity() {
        ExternalIdentity identity = verifierReturning(googleToken(Map.of(
                "sub", "google-subject-1",
                "email", "ada@example.com",
                "email_verified", true,
                "name", "Ada Lovelace"))).verify("any-token");

        assertThat(identity.subject()).isEqualTo("google-subject-1");
        assertThat(identity.email()).isEqualTo("ada@example.com");
        assertThat(identity.displayName()).isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("a withheld profile name is not an error")
    void missingNameIsAllowed() {
        ExternalIdentity identity = verifierReturning(googleToken(Map.of(
                "sub", "google-subject-1",
                "email", "ada@example.com",
                "email_verified", true))).verify("any-token");

        assertThat(identity.displayName()).isNull();
    }

    @Test
    @DisplayName("an unverified email address is refused")
    void unverifiedEmailIsRefused() {
        // The check this whole class exists for. PATIPP links a Google account to an
        // existing account when the addresses match, so accepting an unverified address
        // would be handing over whoever already owns it.
        assertThatThrownBy(() -> verifierReturning(googleToken(Map.of(
                "sub", "google-subject-1",
                "email", "someone-elses@example.com",
                "email_verified", false))).verify("any-token"))
                .isInstanceOf(UnauthorizedException.class)
                .satisfies(thrown -> assertThat(((UnauthorizedException) thrown).code())
                        .isEqualTo("auth.google_email_unverified"));
    }

    @Test
    @DisplayName("a missing email_verified claim counts as unverified, not as verified")
    void absentVerificationClaimIsRefused() {
        assertThatThrownBy(() -> verifierReturning(googleToken(Map.of(
                "sub", "google-subject-1",
                "email", "ada@example.com"))).verify("any-token"))
                .isInstanceOf(UnauthorizedException.class)
                .satisfies(thrown -> assertThat(((UnauthorizedException) thrown).code())
                        .isEqualTo("auth.google_email_unverified"));
    }

    @Test
    @DisplayName("email_verified sent as the string \"true\" is accepted")
    void stringVerificationClaimIsAccepted() {
        // Google sends a JSON boolean, but the claim has been serialised as a string by
        // issuers and proxies before now, and rejecting it would look like a Google outage.
        ExternalIdentity identity = verifierReturning(googleToken(Map.of(
                "sub", "google-subject-1",
                "email", "ada@example.com",
                "email_verified", "true"))).verify("any-token");

        assertThat(identity.email()).isEqualTo("ada@example.com");
    }

    @Test
    @DisplayName("a token with no email is refused, because every account is reached by one")
    void missingEmailIsRefused() {
        assertThatThrownBy(() -> verifierReturning(googleToken(Map.of(
                "sub", "google-subject-1",
                "email_verified", true))).verify("any-token"))
                .isInstanceOf(UnauthorizedException.class)
                .satisfies(thrown -> assertThat(((UnauthorizedException) thrown).code())
                        .isEqualTo("auth.google_email_missing"));
    }

    @Test
    @DisplayName("a token the decoder rejects is reported as invalid, without saying why")
    void rejectedTokenIsInvalid() {
        JwtDecoder refuses = token -> {
            throw new BadJwtException("signature mismatch");
        };

        assertThatThrownBy(() -> new GoogleIdTokenVerifier(refuses, CONFIGURED).verify("forged"))
                .isInstanceOf(UnauthorizedException.class)
                .satisfies(thrown -> assertThat(((UnauthorizedException) thrown).code())
                        .isEqualTo("auth.google_token_invalid"))
                // The reason a token failed helps whoever is probing and helps nobody else.
                .hasMessageNotContaining("signature");
    }

    @Test
    @DisplayName("an empty token never reaches the decoder")
    void blankTokenIsRefusedEarly() {
        JwtDecoder explodes = token -> {
            throw new AssertionError("the decoder should not have been called");
        };

        assertThatThrownBy(() -> new GoogleIdTokenVerifier(explodes, CONFIGURED).verify("  "))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("an unconfigured deployment says so, rather than blaming the token")
    void unconfiguredReportsItself() {
        // 503 rather than 401. A client told its token is invalid retries and eventually
        // blames itself; one told the server cannot do this at all can offer another route.
        GoogleAuthProperties notConfigured = new GoogleAuthProperties(null);

        assertThatThrownBy(() ->
                new GoogleIdTokenVerifier(token -> null, notConfigured).verify("any-token"))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(thrown -> assertThat(((ServiceUnavailableException) thrown).code())
                        .isEqualTo("auth.google_not_configured"));
    }

    @Test
    @DisplayName("the placeholder from .env.example counts as unconfigured")
    void placeholderCountsAsUnconfigured() {
        // Otherwise a half-finished setup fails at first sign-in with a token error, and
        // whoever debugs it goes looking at Google rather than at their own .env.
        assertThat(new GoogleAuthProperties("replace_me_xxxxx.apps.googleusercontent.com")
                .isConfigured()).isFalse();
    }

    private static GoogleIdTokenVerifier verifierReturning(Jwt jwt) {
        return new GoogleIdTokenVerifier(token -> jwt, CONFIGURED);
    }

    /** A decoded token, as the verifier sees one after the signature has been checked. */
    private static Jwt googleToken(Map<String, Object> claims) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("decoded-already")
                .header("alg", "RS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
