package com.patipp.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Mints opaque refresh tokens and hashes them for storage.
 *
 * <p>Refresh tokens are random bytes rather than JWTs. A JWT refresh token would be
 * self-validating, which is precisely what we do not want: the whole point of the refresh
 * layer is that the server can revoke a token, and that requires a database lookup anyway.
 * An opaque token also carries no readable claims if it leaks.
 *
 * <p>Only the SHA-256 is stored. SHA-256 rather than BCrypt is correct here: the token is
 * 256 bits of machine-generated entropy, so there is no dictionary to attack and no need
 * for a slow hash, while lookup by hash must stay a single indexed query.
 */
@Component
public class RefreshTokenFactory {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String mintRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM", impossible);
        }
    }
}
