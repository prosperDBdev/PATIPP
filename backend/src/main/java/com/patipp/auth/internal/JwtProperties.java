package com.patipp.auth.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT and refresh-token configuration.
 *
 * <p>The secret is validated at startup rather than at first login. A weak or placeholder
 * signing key is a total authentication bypass, and discovering it when the first user
 * logs in is far too late; failing to boot is the correct behaviour.
 *
 * @param secret            HS256 signing key, supplied as base64 or as raw text via the
 *                          {@code PATIPP_JWT_SECRET} environment variable
 * @param accessTtlMinutes  lifetime of the bearer token held in browser memory
 * @param refreshTtlDays    lifetime of the rotating refresh cookie
 * @param issuer            {@code iss} claim, verified on every parse
 * @param secureCookie      whether the refresh cookie carries the Secure flag; false only
 *                          for plain-HTTP local development
 */
@ConfigurationProperties(prefix = "patipp.jwt")
public record JwtProperties(
        String secret,
        int accessTtlMinutes,
        int refreshTtlDays,
        String issuer,
        boolean secureCookie) {

    private static final int MINIMUM_KEY_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "PATIPP_JWT_SECRET is not set. Generate one with: openssl rand -base64 48");
        }
        if (secret.startsWith("replace_me")) {
            throw new IllegalStateException(
                    "PATIPP_JWT_SECRET is still the placeholder from .env.example. "
                            + "Generate a real one with: openssl rand -base64 48");
        }
        if (decodeKey(secret).length < MINIMUM_KEY_BYTES) {
            throw new IllegalStateException(
                    "PATIPP_JWT_SECRET must decode to at least " + MINIMUM_KEY_BYTES
                            + " bytes for HS256. Generate one with: openssl rand -base64 48");
        }
        if (accessTtlMinutes <= 0 || accessTtlMinutes > 240) {
            throw new IllegalStateException("patipp.jwt.access-ttl-minutes must be between 1 and 240");
        }
        if (refreshTtlDays <= 0 || refreshTtlDays > 365) {
            throw new IllegalStateException("patipp.jwt.refresh-ttl-days must be between 1 and 365");
        }
        issuer = (issuer == null || issuer.isBlank()) ? "patipp" : issuer;
    }

    /** Base64 is tried first so a generated key keeps its full entropy; raw text is a fallback. */
    public static byte[] decodeKey(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public byte[] keyBytes() {
        return decodeKey(secret);
    }
}
