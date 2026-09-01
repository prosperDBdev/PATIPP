package com.patipp.auth.domain;

import com.patipp.common.id.UuidV7;
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
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
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

    public static String normaliseEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
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
