package com.patipp.auth.internal;

import com.patipp.common.security.AuthenticatedUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies short-lived access tokens.
 *
 * <p>Access tokens are deliberately stateless and short-lived: there is no revocation list
 * to consult on every request. Revocation is handled at the refresh layer, where tokens
 * are stored and can be invalidated, which is why the access lifetime is capped in
 * {@link JwtProperties} - it bounds how long a revoked session can survive.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String TOKEN_TYPE_CLAIM = "typ";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String EMAIL_CLAIM = "email";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.keyBytes());
    }

    public IssuedAccessToken issueAccessToken(UUID userId, String email, Instant now) {
        Instant expiresAt = now.plus(Duration.ofMinutes(properties.accessTtlMinutes()));

        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .claim(EMAIL_CLAIM, email)
                .signWith(signingKey)
                .compact();

        return new IssuedAccessToken(token, expiresAt, properties.accessTtlMinutes() * 60L);
    }

    /**
     * Verifies signature, issuer and expiry.
     *
     * <p>Returns empty rather than throwing: a bad token on an optional-auth route is an
     * ordinary event, not an exceptional one, and the filter decides what it means.
     */
    public Optional<AuthenticatedUser> verifyAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // A refresh token must never be accepted as a bearer credential.
            if (!ACCESS_TOKEN_TYPE.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
                return Optional.empty();
            }

            UUID userId = UUID.fromString(claims.getSubject());
            return Optional.of(new AuthenticatedUser(userId, claims.get(EMAIL_CLAIM, String.class)));

        } catch (JwtException | IllegalArgumentException invalid) {
            // Logged at DEBUG only: expired tokens are routine and would otherwise drown the log.
            log.debug("Rejected access token: {}", invalid.getMessage());
            return Optional.empty();
        }
    }

    public record IssuedAccessToken(String token, Instant expiresAt, long expiresInSeconds) {
    }
}
