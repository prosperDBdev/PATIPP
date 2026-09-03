package com.patipp.sessions.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One sitting: a set of questions served, answered and scored.
 *
 * <p>The same row shape serves practice, exams, interviews and flashcard review. What differs
 * between them is the {@link SessionMode} and the handler that interprets it, not the table.
 */
@Entity
@Table(name = "study_sessions")
public class StudySession extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false, length = 24)
    private SessionMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SessionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, updatable = false)
    private Map<String, Object> config = new HashMap<>();

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private Instant startedAt;

    /** Null for practice. Set by exam mode in Phase 4, where the server owns the clock. */
    @Column(name = "deadline_at", updatable = false)
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "active_ms", nullable = false)
    private long activeMs;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "answered_count", nullable = false)
    private int answeredCount;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_breakdown")
    private Map<String, Object> scoreBreakdown;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected StudySession() {
        // for JPA
    }

    public static StudySession start(UUID userId, UUID spaceId, SessionMode mode,
                                     Map<String, Object> config, Instant deadlineAt) {
        StudySession session = new StudySession();
        session.id = UuidV7.generate();
        session.userId = userId;
        session.preparationSpaceId = spaceId;
        session.mode = mode;
        session.status = SessionStatus.IN_PROGRESS;
        session.config = config == null ? new HashMap<>() : new HashMap<>(config);
        session.deadlineAt = deadlineAt;
        return session;
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

    public UUID preparationSpaceId() {
        return preparationSpaceId;
    }

    public SessionMode mode() {
        return mode;
    }

    public SessionStatus status() {
        return status;
    }

    public Map<String, Object> config() {
        return config;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public long activeMs() {
        return activeMs;
    }

    public int totalItems() {
        return totalItems;
    }

    public int answeredCount() {
        return answeredCount;
    }

    public int correctCount() {
        return correctCount;
    }

    public BigDecimal score() {
        return score;
    }

    public Map<String, Object> scoreBreakdown() {
        return scoreBreakdown;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isFinished() {
        return status.isFinished();
    }

    public void setTotalItems(int count) {
        this.totalItems = count;
    }

    /**
     * Records one answered question.
     *
     * <p>Counters are maintained here rather than recomputed from the attempt log on every
     * read: the session screen asks for them after every answer, and the log is the thing
     * they can always be rebuilt from if they ever drift.
     */
    public void recordAnswer(boolean correct, int timeSpentMs) {
        this.answeredCount++;
        if (correct) {
            this.correctCount++;
        }
        this.activeMs += Math.max(0, timeSpentMs);
    }

    public void finish(SessionStatus finalStatus, Instant when,
                       BigDecimal finalScore, Map<String, Object> breakdown) {
        this.status = finalStatus;
        this.submittedAt = when;
        this.score = finalScore;
        this.scoreBreakdown = breakdown;
    }
}
