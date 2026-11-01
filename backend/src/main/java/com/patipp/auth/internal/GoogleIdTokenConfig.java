package com.patipp.auth.internal;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the decoder that decides whether a Google ID token is genuine.
 *
 * <p>Kept apart from {@link GoogleIdTokenVerifier} so the verifier can be handed a stub in
 * tests. The alternative - a verifier that constructs its own decoder - would make every
 * test of the claim rules depend on reaching Google over the network, which in practice
 * means those rules stop being tested at all.
 */
@Configuration
class GoogleIdTokenConfig {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenConfig.class);

    /** Google's published signing keys. Rotated by Google; fetched and cached by Nimbus. */
    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /**
     * Google issues ID tokens with both spellings and documents that both must be accepted.
     * A verifier that allows only one works until the day it does not, and the failure looks
     * like an outage rather than a configuration error.
     */
    private static final Set<String> ACCEPTED_ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");

    /**
     * A minute of slack, for the ordinary case of a client clock that is slightly out. Wider
     * than this starts accepting tokens Google considers expired.
     */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    @Bean
    JwtDecoder googleIdTokenDecoder(GoogleAuthProperties properties) {
        if (!properties.isConfigured()) {
            log.info("Google sign-in is disabled: patipp.google.client-id is not set.");
            // A decoder that always refuses, so the bean graph is the same either way and
            // the rest of the application needs no conditional wiring. It is never actually
            // reached: the verifier checks configuration first and reports it properly.
            return token -> {
                throw new JwtException("Google sign-in is not configured.");
            };
        }

        if (!properties.looksLikeGoogleClientId()) {
            log.warn("patipp.google.client-id does not look like a Google client id "
                    + "(expected it to end with .apps.googleusercontent.com). Google sign-in "
                    + "will reject every token if this is the wrong value.");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URI)
                // Pinned, rather than accepting whatever the token's header asks for.
                // Letting the token choose its own algorithm is how signature verification
                // gets talked out of happening at all.
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                issuerValidator(),
                audienceValidator(properties.clientId())));

        log.info("Google sign-in is enabled.");
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> issuerValidator() {
        return jwt -> {
            Object claim = jwt.getClaims().get(JwtClaimNames.ISS);
            // Read as a raw claim rather than through getIssuer(): Spring converts iss to a
            // URL when it parses as one, so the bare "accounts.google.com" form and the
            // https form arrive here as different types.
            String issuer = claim == null ? null : String.valueOf(claim);
            return ACCEPTED_ISSUERS.contains(issuer)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("The token was not issued by Google.");
        };
    }

    /**
     * The check that makes a token <em>ours</em>.
     *
     * <p>Without it, any valid Google ID token would be accepted - including one issued to a
     * completely different application, which that application's operator could simply read
     * out of their own logs and replay here as any of their users.
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String clientId) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            return audience != null && audience.contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("The token was not issued for this application.");
        };
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", description, null));
    }
}
