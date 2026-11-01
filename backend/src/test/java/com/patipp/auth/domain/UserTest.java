package com.patipp.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for the two User factories. No Spring, no database - these are the rules the
 * model enforces on its own, and they are worth checking at this speed because every one of
 * them exists to stop a CHECK constraint failing at the very end of a sign-in.
 */
class UserTest {

    @Test
    @DisplayName("a provider account has no password")
    void providerAccountHasNoPassword() {
        User user = User.fromProvider("ada@example.com", "Ada Lovelace", "Europe/London");

        assertThat(user.passwordHash()).isNull();
        assertThat(user.hasPassword()).isFalse();
        assertThat(user.email()).isEqualTo("ada@example.com");
        assertThat(user.displayName()).isEqualTo("Ada Lovelace");
        assertThat(user.timezone()).isEqualTo("Europe/London");
    }

    @Test
    @DisplayName("a registered account has one")
    void registeredAccountHasPassword() {
        User user = User.register("ada@example.com", "$2a$10$notarealhash", "Ada", "UTC");

        assertThat(user.hasPassword()).isTrue();
    }

    @Test
    @DisplayName("the address is normalised whichever factory built the user")
    void addressIsNormalised() {
        assertThat(User.fromProvider("  Ada@Example.COM ", "Ada", "UTC").email())
                .isEqualTo("ada@example.com");
    }

    @Test
    @DisplayName("a withheld provider name falls back to the local part of the address")
    void missingDisplayNameFallsBackToLocalPart() {
        // Google lets a user withhold their profile name, so this is a real response and
        // not a defensive hypothetical. A blank would fail users_display_name_len.
        assertThat(User.fromProvider("ada@example.com", null, "UTC").displayName())
                .isEqualTo("ada");
        assertThat(User.fromProvider("ada@example.com", "   ", "UTC").displayName())
                .isEqualTo("ada");
    }

    @Test
    @DisplayName("an over-long provider name is clamped to the 80 characters the column allows")
    void longDisplayNameIsClamped() {
        String tooLong = "x".repeat(200);

        assertThat(User.fromProvider("ada@example.com", tooLong, "UTC").displayName())
                .hasSize(80);
    }

    @Test
    @DisplayName("a missing timezone becomes UTC rather than a constraint violation")
    void missingTimezoneBecomesUtc() {
        assertThat(User.fromProvider("ada@example.com", "Ada", null).timezone()).isEqualTo("UTC");
        assertThat(User.fromProvider("ada@example.com", "Ada", "  ").timezone()).isEqualTo("UTC");
    }
}
