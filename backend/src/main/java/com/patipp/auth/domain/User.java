package com.patipp.auth.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false)
    private String email;

    // Nullable since V10: an account created through a provider has no password. The
    // obligation moved to the application, which creates a user and its identity in one
    // transaction, because "has a password OR has an identity" spans two tables.
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false)
    private Map<String, Object> settings = new HashMap<>();

    // created_at and updated_at are written by the database (DEFAULT now() and the
    // set_updated_at trigger), so the JVM clock is never the source of truth.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    protected User() {
        // for JPA
    }

    private User(UUID id, String email, String passwordHash, String displayName, String timezone) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.timezone = timezone;
    }

    /**
     * The single place a User comes into existence. The address is lower-cased here
     * because a CHECK constraint in the schema rejects anything else: normalisation is a
     * rule of the model, not a courtesy of whichever caller happens to be writing.
     */
    public static User register(String email, String passwordHash, String displayName, String timezone) {
        return new User(
                UuidV7.generate(),
                normaliseEmail(email),
                passwordHash,
                displayName.strip(),
                (timezone == null || timezone.isBlank()) ? "UTC" : timezone.strip());
    }

    /**
     * A user vouched for by an external provider, with no password of their own.
     *
     * <p>The caller must already have verified the provider's assertion. This factory is
     * about shape, not trust: it will happily build a user from any address, so the checks
     * that the token was signed, unexpired and audience-bound belong upstream of it.
     *
     * <p>{@code displayNameHint} is whatever the provider offered, which may be nothing -
     * Google lets a user withhold their profile name. Rather than fail, or write a blank
     * that the CHECK constraint would reject at the very end of a sign-in, the local part
     * of the address is used instead and the result is clamped to the 80 characters the
     * column allows.
     */
    public static User fromProvider(String email, String displayNameHint, String timezone) {
        String normalised = normaliseEmail(email);
        return new User(
                UuidV7.generate(),
                normalised,
                null,
                deriveDisplayName(displayNameHint, normalised),
                (timezone == null || timezone.isBlank()) ? "UTC" : timezone.strip());
    }

    private static String deriveDisplayName(String hint, String normalisedEmail) {
        String candidate = hint == null ? "" : hint.strip();
        if (candidate.isEmpty()) {
            int at = normalisedEmail.indexOf('@');
            candidate = at > 0 ? normalisedEmail.substring(0, at) : normalisedEmail;
        }
        if (candidate.isEmpty()) {
            // Only reachable from a malformed address, which the verifier rejects before
            // it ever gets here. Present so the constraint can never be the thing that
            // reports the problem.
            candidate = "Learner";
        }
        return candidate.length() <= 80 ? candidate : candidate.substring(0, 80);
    }

    public static String normaliseEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public UUID id() {
        return id;
    }

    /** Required by Persistable so Spring Data can tell an insert from an update. */
    @Override
    public UUID getId() {
        return id;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    /**
     * False for an account that only ever signed in through a provider.
     *
     * <p>Password login checks this explicitly rather than relying on the encoder to reject
     * a null hash. {@code BCryptPasswordEncoder} does return false for one, but depending on
     * that leaves the most security-sensitive branch in the system resting on the internals
     * of a library call instead of on a stated rule.
     */
    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public String displayName() {
        return displayName;
    }

    public String timezone() {
        return timezone;
    }

    public Map<String, Object> settings() {
        return settings;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActiveAt() {
        return lastActiveAt;
    }

    public void rename(String newDisplayName) {
        this.displayName = newDisplayName.strip();
    }

    public void changeTimezone(String newTimezone) {
        this.timezone = newTimezone.strip();
    }

    public void replaceSettings(Map<String, Object> newSettings) {
        this.settings = newSettings == null ? new HashMap<>() : new HashMap<>(newSettings);
    }

    public void touchLastActive(Instant when) {
        this.lastActiveAt = when;
    }
}
