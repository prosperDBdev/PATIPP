package com.patipp.sessions.domain;

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
 * A saved exam setup: "50 questions, 60 minutes, all subjects".
 *
 * <p>Stores the same shape a start-session request takes, so sitting a template is literally
 * replaying that request. It holds no questions of its own - the paper is drawn fresh each
 * time from the bank as it stands, which is what makes a second sitting a real re-test rather
 * than a memory exercise.
 */
@Entity
@Table(name = "exam_templates")
public class ExamTemplate extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "times_used", nullable = false)
    private int timesUsed;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected ExamTemplate() {
        // for JPA
    }

    public static ExamTemplate create(UUID spaceId, String name, Map<String, Object> config) {
        ExamTemplate template = new ExamTemplate();
        template.id = UuidV7.generate();
        template.preparationSpaceId = spaceId;
        template.name = name.strip();
        template.config = config == null ? new HashMap<>() : new HashMap<>(config);
        return template;
    }

    public UUID id() {
        return id;
    }

    /** Required by Persistable so Spring Data can tell an insert from an update. */
    @Override
    public UUID getId() {
        return id;
    }

    public UUID preparationSpaceId() {
        return preparationSpaceId;
    }

    public String name() {
        return name;
    }

    public Map<String, Object> config() {
        return config;
    }

    public int timesUsed() {
        return timesUsed;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void rename(String newName) {
        this.name = newName.strip();
    }

    public void reconfigure(Map<String, Object> newConfig) {
        this.config = newConfig == null ? new HashMap<>() : new HashMap<>(newConfig);
    }

    /** Counted so the setup you actually sit can be offered ahead of the one you first made. */
    public void recordUse(Instant when) {
        this.timesUsed++;
        this.lastUsedAt = when;
    }

    public void archive(Instant when) {
        if (this.archivedAt == null) {
            this.archivedAt = when;
        }
    }
}
