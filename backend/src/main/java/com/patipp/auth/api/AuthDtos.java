package com.patipp.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request and response payloads for the authentication endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * @param password BCrypt only considers the first 72 bytes of input, so anything longer
     *                 is silently truncated and two different long passwords could collide.
     *                 The limit is enforced here rather than left as a surprise.
     */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(min = 1, max = 80) String displayName,
            @Size(max = 64) String timezone) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    /**
     * A Google ID token, obtained in the browser and posted here once to be exchanged for a
     * PATIPP session.
     *
     * @param idToken  the raw JWT. The size cap is a cheap guard rather than a real check:
     *                 a Google ID token is comfortably under a kilobyte, and refusing
     *                 anything absurd means signature verification is never asked to chew
     *                 through a megabyte of attacker-chosen input. What makes the token
     *                 trustworthy is checked in {@code GoogleIdTokenVerifier}, not here.
     * @param timezone the browser's IANA zone, used <em>only</em> when this call creates the
     *                 account. Google does not tell us one, and without it every learner who
     *                 arrived through Google would sit in UTC and see their study days
     *                 attributed to the wrong date. It is never applied to an existing
     *                 account: whatever the learner chose in their settings wins over
     *                 whatever laptop they happen to be signing in from.
     */
    public record GoogleSignInRequest(
            @NotBlank @Size(max = 4096) String idToken,
            @Size(max = 64) String timezone) {
    }

    /**
     * The refresh token is deliberately absent: it travels only as an httpOnly cookie, so
     * it is never readable by JavaScript and never lands in a log or an error report.
     */
    public record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            UserSummary user) {
    }

    public record UserSummary(
            UUID id,
            String email,
            String displayName,
            String timezone) {
    }
}
