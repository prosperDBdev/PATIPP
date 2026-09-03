package com.patipp.sessions.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One question served within a session, at a fixed position.
 *
 * <p>The question <em>version</em> is pinned here, not just the question. An edit after this
 * point creates a new version and leaves this one exactly as it was answered, so a score from
 * January still refers to the text that produced it.
 */
@Entity
@Table(name = "session_items")
public class SessionItem extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "position", nullable = false, updatable = false)
    private short position;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "question_version_id", nullable = false, updatable = false)
    private UUID questionVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private ItemState state;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "first_viewed_at")
    private Instant firstViewedAt;

    @Column(name = "time_spent_ms", nullable = false)
    private int timeSpentMs;

    /**
     * Why the engine chose this question. Random in Phase 3; real reasoning from Phase 5.
     *
     * <p>Recorded from the start because "why did it show me this?" has to be answerable. An
     * adaptive engine whose choices cannot be inspected is one nobody trusts and nobody can
     * debug.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection_reason", nullable = false, updatable = false)
    private Map<String, Object> selectionReason = new HashMap<>();

    protected SessionItem() {
        // for JPA
    }

    public static SessionItem of(UUID sessionId, UUID spaceId, short position,
                                 UUID questionId, UUID questionVersionId,
                                 Map<String, Object> selectionReason) {
        SessionItem item = new SessionItem();
        item.id = UuidV7.generate();
        item.sessionId = sessionId;
        item.preparationSpaceId = spaceId;
        item.position = position;
        item.questionId = questionId;
        item.questionVersionId = questionVersionId;
        item.state = ItemState.UNSEEN;
        item.selectionReason = selectionReason == null ? new HashMap<>() : new HashMap<>(selectionReason);
        return item;
    }

    public UUID id() {
        return id;
    }

    /** Required by Persistable so Spring Data can tell an insert from an update. */
    @Override
    public UUID getId() {
        return id;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public short position() {
        return position;
    }

    public UUID questionId() {
        return questionId;
    }

    public UUID questionVersionId() {
        return questionVersionId;
    }

    public ItemState state() {
        return state;
    }

    public UUID attemptId() {
        return attemptId;
    }

    public Instant firstViewedAt() {
        return firstViewedAt;
    }

    public int timeSpentMs() {
        return timeSpentMs;
    }

    public Map<String, Object> selectionReason() {
        return selectionReason;
    }

    public boolean isAnswered() {
        return state == ItemState.ANSWERED;
    }

    /** Idempotent: the first view is the one that counts, however often the item is re-fetched. */
    public void markViewed(Instant when) {
        if (this.firstViewedAt == null) {
            this.firstViewedAt = when;
        }
        if (this.state == ItemState.UNSEEN) {
            this.state = ItemState.VIEWED;
        }
    }

    public void markAnswered(UUID attempt, int elapsedMs) {
        this.state = ItemState.ANSWERED;
        this.attemptId = attempt;
        this.timeSpentMs = Math.max(0, elapsedMs);
    }
}
