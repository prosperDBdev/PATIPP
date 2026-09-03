package com.patipp.preparations.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A kind of preparation - academic exam, interview, certification, coding test, and so on.
 *
 * <p>This is the extensibility mechanism. A preparation type is a <em>row</em>, never a
 * subclass. Everything that varies between kinds of preparation lives in {@link #blueprint}
 * as declarative configuration: which question types are allowed, which session modes
 * exist, which scoring and difficulty and scheduling policies apply, how readiness is
 * weighted. The engine reads the blueprint and resolves policy keys through registries; it
 * never switches on {@link #key}.
 *
 * <p>The practical consequence is that adding "PMP Certification" is an INSERT, and adding
 * a genuinely novel format costs one new strategy implementation rather than a parallel
 * copy of the session, scoring and analytics stack.
 */
@Entity
@Table(name = "preparation_types")
public class PreparationType extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "key", nullable = false, updatable = false, length = 64)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "icon", length = 32)
    private String icon;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blueprint", nullable = false)
    private Map<String, Object> blueprint = new HashMap<>();

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected PreparationType() {
        // for JPA
    }

    public static PreparationType custom(String key, String name, String description, String icon,
                                         Map<String, Object> blueprint, UUID ownerId) {
        PreparationType type = new PreparationType();
        type.id = UuidV7.generate();
        type.key = key;
        type.name = name;
        type.description = description;
        type.icon = icon;
        type.system = false;
        type.blueprint = new HashMap<>(blueprint);
        type.createdBy = ownerId;
        return type;
    }

    public UUID id() {
        return id;
    }

    /** Required by Persistable so Spring Data can tell an insert from an update. */
    @Override
    public UUID getId() {
        return id;
    }

    public String key() {
        return key;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String icon() {
        return icon;
    }

    public boolean isSystem() {
        return system;
    }

    public Map<String, Object> blueprint() {
        return blueprint;
    }

    public UUID createdBy() {
        return createdBy;
    }
}
