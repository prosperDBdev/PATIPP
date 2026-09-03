package com.patipp.preparations.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A self-contained world in which one user prepares for one thing.
 *
 * <p>This is the partition key of the entire system. Subjects, topics, questions, attempts,
 * review state, mastery and readiness all hang off a space, and performance in one space is
 * completely independent of performance in another. "NIIT Semester 2" and "Java Backend
 * Interview" share no state and the whole engine.
 */
@Entity
@Table(name = "preparation_spaces")
public class PreparationSpace extends AssignedIdEntity<UUID> {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "preparation_type_id", nullable = false)
    private PreparationType preparationType;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Column(name = "target_score")
    private Short targetScore;

    /**
     * Overrides shallow-merged over the type's blueprint. This is how one space gets its own
     * difficulty rules or readiness weights without needing a preparation type of its own.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected PreparationSpace() {
        // for JPA
    }

    public static PreparationSpace create(UUID userId, PreparationType type, String name,
                                          String description, LocalDate targetDate,
                                          Short targetScore, Map<String, Object> config) {
        PreparationSpace space = new PreparationSpace();
        space.id = UuidV7.generate();
        space.userId = userId;
        space.preparationType = type;
        space.name = name.strip();
        space.description = description;
        space.status = STATUS_ACTIVE;
        space.targetDate = targetDate;
        space.targetScore = targetScore;
        space.config = config == null ? new HashMap<>() : new HashMap<>(config);
        return space;
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

    public PreparationType preparationType() {
        return preparationType;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String status() {
        return status;
    }

    public LocalDate targetDate() {
        return targetDate;
    }

    public Short targetScore() {
        return targetScore;
    }

    public Map<String, Object> config() {
        return config;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void rename(String newName) {
        this.name = newName.strip();
    }

    public void describe(String newDescription) {
        this.description = newDescription;
    }

    public void retarget(LocalDate newTargetDate, Short newTargetScore) {
        this.targetDate = newTargetDate;
        this.targetScore = newTargetScore;
    }

    public void changeStatus(String newStatus) {
        this.status = newStatus;
    }

    public void replaceConfig(Map<String, Object> newConfig) {
        this.config = newConfig == null ? new HashMap<>() : new HashMap<>(newConfig);
    }

    /**
     * Soft delete. Archiving rather than deleting because attempts, sessions and mastery
     * rows will reference this space, and a user who archives a space has not asked to
     * destroy months of their own history.
     */
    public void archive(Instant when) {
        if (this.archivedAt == null) {
            this.archivedAt = when;
            this.status = STATUS_ARCHIVED;
        }
    }
}
