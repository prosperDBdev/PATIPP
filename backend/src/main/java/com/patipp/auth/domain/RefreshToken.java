package com.patipp.auth.domain;

import com.patipp.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single issued refresh token.
 *
 * <p>Only the SHA-256 of the token is stored, so a database disclosure does not hand an
 * attacker a set of usable sessions. Rotation links each token to its successor through
 * {@code replacedById}, which is what makes reuse detection possible: presenting a token
 * that has already been replaced means the token leaked, and the entire chain is revoked.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, insertable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "user_agent", updatable = false)
    private String userAgent;

    protected RefreshToken() {
        // for JPA
    }

    public static RefreshToken issue(UUID userId, String tokenHash, Instant expiresAt, String userAgent) {
        RefreshToken token = new RefreshToken();
        token.id = UuidV7.generate();
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.userAgent = truncate(userAgent);
        return token;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public UUID replacedById() {
        return replacedById;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** True once this token has been rotated away; presenting it again is a reuse signal. */
    public boolean isAlreadyRotated() {
        return replacedById != null;
    }

    public void revoke(Instant when) {
        if (this.revokedAt == null) {
            this.revokedAt = when;
        }
    }

    public void replaceWith(UUID successorId, Instant when) {
        this.replacedById = successorId;
        revoke(when);
    }
}
