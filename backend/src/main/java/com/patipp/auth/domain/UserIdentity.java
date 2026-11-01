package com.patipp.auth.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One external account a learner can sign in with.
 *
 * <p>A user has zero or more of these. Zero means a password-only account; one means they
 * can use that provider; a user with a password <em>and</em> an identity can use either,
 * which is the case a single {@code auth_provider} column on {@code users} could not
 * express and the reason this is a table.
 *
 * <p>Identities are resolved by {@link #externalId()} - the provider's immutable subject -
 * and never by email. Google's own guidance is explicit that an address can change hands,
 * so an address is evidence about a person, not a name for them. {@link #email()} is kept
 * for auditing and for telling the learner which account is linked.
 */
@Entity
@Table(name = "user_identities")
public class UserIdentity extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 16)
    private AuthProvider provider;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "email", nullable = false)
    private String email;

    // Written by the database default, like users.created_at.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserIdentity() {
        // for JPA
    }

    /**
     * Links an external account to a PATIPP user.
     *
     * <p>The address is normalised here for the same reason it is on {@link User}: a CHECK
     * constraint rejects anything else, so normalisation is a rule of the model rather than
     * a courtesy of whichever caller happens to be writing.
     */
    public static UserIdentity link(UUID userId,
                                    AuthProvider provider,
                                    String externalId,
                                    String email,
                                    Instant when) {
        UserIdentity identity = new UserIdentity();
        identity.id = UuidV7.generate();
        identity.userId = userId;
        identity.provider = provider;
        identity.externalId = externalId;
        identity.email = User.normaliseEmail(email);
        identity.lastLoginAt = when;
        return identity;
    }

    public UUID id() {
        return id;
    }

    /** Required by Persistable so Spring Data can tell an insert from an update. */
    @Override
    public UUID getId() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public AuthProvider provider() {
        return provider;
    }

    public String externalId() {
        return externalId;
    }

    public String email() {
        return email;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastLoginAt() {
        return lastLoginAt;
    }

    /**
     * Records a sign-in, and follows an address change at the provider.
     *
     * <p>The email is refreshed because the provider is the authority on it and a stale
     * copy would make the audit trail misleading. It is only ever a copy: the identity is
     * still resolved by subject, so a changed address cannot move an account.
     */
    public void recordLogin(String assertedEmail, Instant when) {
        this.email = User.normaliseEmail(assertedEmail);
        this.lastLoginAt = when;
    }
}
