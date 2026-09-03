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
 * A topic within a subject, or a subtopic within a topic.
 *
 * <p>Self-referencing, so "subtopic" needs no second table: React to Hooks to useEffect
 * dependencies is three rows of the same shape. Depth is capped at two levels below the
 * root because deeper trees are unusable in a UI and make mastery roll-ups meaningless -
 * if a concept needs four levels of nesting to express, it wants to be its own subject.
 */
@Entity
@Table(name = "topics")
public class Topic extends AssignedIdEntity<UUID> {

    public static final int MAX_DEPTH = 2;
    public static final String PATH_SEPARATOR = " / ";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "parent_topic_id", updatable = false)
    private UUID parentTopicId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "position", nullable = false)
    private short position;

    @Column(name = "weight", nullable = false, precision = 6, scale = 2)
    private BigDecimal weight;

    @Column(name = "depth", nullable = false)
    private short depth;

    /** Materialised path, maintained by the service so breadcrumbs cost no extra queries. */
    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Topic() {
        // for JPA
    }

    public static Topic create(UUID preparationSpaceId, UUID subjectId, Topic parent,
                               String name, String description, short position, BigDecimal weight) {
        Topic topic = new Topic();
        topic.id = UuidV7.generate();
        topic.preparationSpaceId = preparationSpaceId;
        topic.subjectId = subjectId;
        topic.parentTopicId = parent == null ? null : parent.id();
        topic.name = name.strip();
        topic.description = description;
        topic.position = position;
        topic.weight = weight == null ? BigDecimal.ONE : weight;
        topic.depth = parent == null ? 0 : (short) (parent.depth() + 1);
        topic.path = parent == null ? topic.name : parent.path() + PATH_SEPARATOR + topic.name;
        return topic;
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

    public UUID subjectId() {
        return subjectId;
    }

    public UUID parentTopicId() {
        return parentTopicId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public short position() {
        return position;
    }

    public BigDecimal weight() {
        return weight;
    }

    public short depth() {
        return depth;
    }

    public String path() {
        return path;
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

    public void reposition(short newPosition) {
        this.position = newPosition;
    }

    public void reweigh(BigDecimal newWeight) {
        this.weight = newWeight;
    }

    /**
     * Recomputes this node's path from its parent's. Called for the renamed node and then
     * for every descendant, because a materialised path is only worth having if it is
     * never stale.
     */
    public void rebuildPath(String parentPath) {
        this.path = (parentPath == null || parentPath.isBlank())
                ? this.name
                : parentPath + PATH_SEPARATOR + this.name;
    }

    public void archive(Instant when) {
        if (this.archivedAt == null) {
            this.archivedAt = when;
        }
    }
}
