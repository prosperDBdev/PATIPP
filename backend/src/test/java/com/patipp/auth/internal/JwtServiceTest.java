package com.patipp.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.patipp.common.security.AuthenticatedUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("a-test-signing-key-of-at-least-32-bytes!".getBytes());

    private final JwtProperties properties =
            new JwtProperties(SECRET, 15, 30, "patipp-test", false);
    private final JwtService jwtService = new JwtService(properties);

    @Test
    @DisplayName("a freshly issued token verifies back to the same user")
    void roundTrip() {
        UUID userId = UUID.randomUUID();

        var issued = jwtService.issueAccessToken(userId, "ada@example.com", Instant.now());
        AuthenticatedUser user = jwtService.verifyAccessToken(issued.token()).orElseThrow();

        assertThat(user.id()).isEqualTo(userId);
        assertThat(user.email()).isEqualTo("ada@example.com");
        assertThat(issued.expiresInSeconds()).isEqualTo(15 * 60);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() {
        var issued = jwtService.issueAccessToken(
                UUID.randomUUID(), "old@example.com",
                Instant.now().minus(2, ChronoUnit.HOURS));

        assertThat(jwtService.verifyAccessToken(issued.token())).isEmpty();
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void tokenFromAnotherKeyIsRejected() {
        JwtService otherIssuer = new JwtService(new JwtProperties(
                Base64.getEncoder().encodeToString("a-completely-different-signing-key!!".getBytes()),
                15, 30, "patipp-test", false));

        var foreign = otherIssuer.issueAccessToken(UUID.randomUUID(), "mallory@example.com", Instant.now());

        assertThat(jwtService.verifyAccessToken(foreign.token())).isEmpty();
    }

    @Test
    @DisplayName("a token from a different issuer is rejected")
    void tokenFromAnotherIssuerIsRejected() {
        JwtService otherIssuer = new JwtService(new JwtProperties(SECRET, 15, 30, "somebody-else", false));
        var foreign = otherIssuer.issueAccessToken(UUID.randomUUID(), "x@example.com", Instant.now());

        assertThat(jwtService.verifyAccessToken(foreign.token())).isEmpty();
    }

    @Test
    @DisplayName("malformed input is rejected rather than throwing")
    void malformedTokensAreRejected() {
        assertThat(jwtService.verifyAccessToken("")).isEmpty();
        assertThat(jwtService.verifyAccessToken("not-a-jwt")).isEmpty();
        assertThat(jwtService.verifyAccessToken("a.b.c")).isEmpty();
    }

    @Test
    @DisplayName("a weak or placeholder signing key stops the application starting")
    void weakSecretsAreRefusedAtStartup() {
        // Discovering a broken signing key when the first user logs in is far too late.
        assertThatThrownBy(() -> new JwtProperties("short", 15, 30, "patipp", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");

        assertThatThrownBy(() -> new JwtProperties(
                "replace_me_openssl_rand_base64_48", 15, 30, "patipp", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");

        assertThatThrownBy(() -> new JwtProperties(null, 15, 30, "patipp", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PATIPP_JWT_SECRET");
    }
}
