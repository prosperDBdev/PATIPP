package com.patipp.auth.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google sign-in configuration.
 *
 * <p>There is exactly one value, and it is <em>not</em> a secret: the OAuth client id is
 * embedded in the page that renders the sign-in button, so anyone can read it. It still
 * comes from the environment because it differs between local development and production,
 * and because a client id committed to the repository is one more thing to remember to
 * change when it rotates.
 *
 * <p>Absent configuration is allowed and means the feature is off. That is deliberate:
 * unlike the JWT signing key, a missing client id is not a security hole, and refusing to
 * boot over an optional feature would stop the whole API for anyone who has not set Google
 * up yet - including the test suite.
 *
 * @param clientId the OAuth 2.0 Web application client id from the Google Cloud console,
 *                 which is also the {@code aud} claim every accepted ID token must carry
 */
@ConfigurationProperties(prefix = "patipp.google")
public record GoogleAuthProperties(String clientId) {

    private static final String PLACEHOLDER = "replace_me";

    /** False when Google sign-in has not been set up on this deployment. */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && !clientId.startsWith(PLACEHOLDER);
    }

    /**
     * True when the value looks like a Google client id. Only ever used to warn: the format
     * is stable but it is Google's to change, and refusing to start over a cosmetic check on
     * an optional feature would be a worse failure than the one it is guarding against.
     */
    public boolean looksLikeGoogleClientId() {
        return isConfigured() && clientId.endsWith(".apps.googleusercontent.com");
    }
}
