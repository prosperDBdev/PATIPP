package com.patipp.attempts.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One answer, recorded permanently.
 *
 * <p>This is the source of truth for everything computed later - topic mastery, review
 * schedules, readiness scores. All of those are derived tables that can be dropped and
 * rebuilt by replaying this log, which is what makes replacing the spaced-repetition or
 * selection algorithm in a later phase a safe operation rather than a destructive one.
 *
 * <p>That guarantee only holds if the log is genuinely immutable, so immutability is enforced
 * in three places rather than trusted once: this class exposes no setters,
 * {@link Immutable} stops Hibernate from ever issuing an UPDATE for it, and a database
 * trigger rejects any UPDATE that reaches the table by another route.
 */
@Entity
@Immutable
@Table(name = "question_attempts")
public class QuestionAttempt extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "question_version_id", nullable = false, updatable = false)
    private UUID questionVersionId;

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    // Denormalised deliberately. Analytics never needs a join, and moving a question to a
    // different topic later must not rewrite what was actually practised at the time.
    @Column(name = "subject_id", updatable = false)
    private UUID subjectId;

    @Column(name = "topic_id", updatable = false)
    private UUID topicId;

    @Column(name = "difficulty", nullable = false, updatable = false, length = 16)
    private String difficulty;

    @Column(name = "mode", nullable = false, updatable = false, length = 24)
    private String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answer", nullable = false, updatable = false)
    private Map<String, Object> answer;

    @Column(name = "is_correct", nullable = false, updatable = false)
    private boolean correct;

    /**
     * 0.000 to 1.000, separate from {@link #correct} so partial credit does not inflate
     * accuracy: two of three right on a multi-select scores 0.667 and is still not correct.
     */
    @Column(name = "score", nullable = false, updatable = false, precision = 4, scale = 3)
    private BigDecimal score;

    @Column(name = "grade", updatable = false)
    private Short grade;

    @Column(name = "response_time_ms", updatable = false)
    private Integer responseTimeMs;

    @Column(name = "confidence", updatable = false)
    private Short confidence;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "evaluated_by", nullable = false, updatable = false, length = 8)
    private String evaluatedBy;

    @Column(name = "client_attempt_id", updatable = false)
    private UUID clientAttemptId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected QuestionAttempt() {
        // for JPA
    }

    /**
     * @param attemptNo       1 for the first time this learner saw this question, 2 for the
     *                        second, and so on. Used by the scheduler in Phase 6 to tell
     *                        first-time recall from repeated exposure.
     * @param clientAttemptId supplied by an offline client so a replayed outbox cannot
     *                        double-count; null for answers submitted online.
     */
    public static QuestionAttempt record(UUID userId, UUID spaceId, UUID questionId,
                                         UUID questionVersionId, UUID sessionId,
                                         UUID subjectId, UUID topicId, String difficulty,
                                         String mode, Map<String, Object> answer,
                                         boolean correct, BigDecimal score, Short grade,
                                         Integer responseTimeMs, Short confidence,
                                         int attemptNo, String evaluatedBy,
                                         UUID clientAttemptId) {
        QuestionAttempt attempt = new QuestionAttempt();
        attempt.id = UuidV7.generate();
        attempt.userId = userId;
        attempt.preparationSpaceId = spaceId;
        attempt.questionId = questionId;
        attempt.questionVersionId = questionVersionId;
        attempt.sessionId = sessionId;
        attempt.subjectId = subjectId;
        attempt.topicId = topicId;
        attempt.difficulty = difficulty;
        attempt.mode = mode;
        attempt.answer = answer;
        attempt.correct = correct;
        attempt.score = score;
        attempt.grade = grade;
        attempt.responseTimeMs = responseTimeMs;
        attempt.confidence = confidence;
        attempt.attemptNo = attemptNo;
        // Recorded rather than assumed: a score is only interpretable alongside who produced
        // it, and a readiness figure that cannot say how much of itself came from
        // self-assessment is one nobody can audit.
        attempt.evaluatedBy = evaluatedBy == null ? "AUTO" : evaluatedBy;
        attempt.clientAttemptId = clientAttemptId;
        return attempt;
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

    public UUID questionId() {
        return questionId;
    }

    public UUID questionVersionId() {
        return questionVersionId;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public UUID topicId() {
        return topicId;
    }

    public String difficulty() {
        return difficulty;
    }

    public String mode() {
        return mode;
    }

    public Map<String, Object> answer() {
        return answer;
    }

    public boolean isCorrect() {
        return correct;
    }

    public BigDecimal score() {
        return score;
    }

    public Short grade() {
        return grade;
    }

    public Integer responseTimeMs() {
        return responseTimeMs;
    }

    public Short confidence() {
        return confidence;
    }

    public int attemptNo() {
        return attemptNo;
    }

    /** AUTO, SELF or MIXED - how this attempt was graded. */
    public String evaluatedBy() {
        return evaluatedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
