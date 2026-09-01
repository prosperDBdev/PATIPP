package com.patipp.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token for one user. Used by logout-everywhere and, more
     * importantly, when reuse of an already-rotated token is detected.
     *
     * <p>{@code flushAutomatically} so pending changes reach the database before the bulk
     * update runs, and {@code clearAutomatically} because a bulk JPQL update goes straight
     * to the database and leaves the persistence context holding stale copies. Without the
     * clear, a token loaded earlier in the same transaction would still look live after
     * being revoked - which is precisely the token an attacker just rotated into.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
