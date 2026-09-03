package com.patipp.curriculum.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A top-level division of a preparation space: HTML, CSS, JavaScript, React.
 *
 * <p>Subjects are the grain at which results are reported ("React 68%, CSS 87%") and they
 * carry the blueprint weighting, so a subject worth 30% of the paper influences readiness
 * accordingly. Topics nest beneath them as a tree.
 *
 * <p>{@code preparationSpaceId} is a plain UUID rather than a mapped association. That is
 * deliberate: it keeps the partition key visible in every query and every constructor, and
 * it stops a lazy association from quietly becoming the route by which code reaches across
 * spaces.
 */
@Entity
@Table(name = "subjects")
public class Subject extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "color", length = 16)
    private String color;

    @Column(name = "position", nullable = false)
    private short position;

    @Column(name = "weight", nullable = false, precision = 6, scale = 2)
    private BigDecimal weight;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Subject() {
        // for JPA
    }

    public static Subject create(UUID preparationSpaceId, String name, String description,
                                 String color, short position, BigDecimal weight) {
        Subject subject = new Subject();
        subject.id = UuidV7.generate();
        subject.preparationSpaceId = preparationSpaceId;
        subject.name = name.strip();
        subject.description = description;
        subject.color = color;
        subject.position = position;
        subject.weight = weight == null ? BigDecimal.ONE : weight;
        return subject;
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

    public String description() {
        return description;
    }

    public String color() {
        return color;
    }

    public short position() {
        return position;
    }

    public BigDecimal weight() {
        return weight;
    }

    public Instant createdAt() {
        return createdAt;
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

    public void recolour(String newColor) {
        this.color = newColor;
    }

    public void reposition(short newPosition) {
        this.position = newPosition;
    }

    public void reweigh(BigDecimal newWeight) {
        this.weight = newWeight;
    }

    public void archive(Instant when) {
        if (this.archivedAt == null) {
            this.archivedAt = when;
        }
    }
}
