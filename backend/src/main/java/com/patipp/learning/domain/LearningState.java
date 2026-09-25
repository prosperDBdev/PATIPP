package com.patipp.learning.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import com.patipp.scheduling.ReviewState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * One question's review schedule for one learner in one space.
 *
 * <p>A thin shell around the pure {@link ReviewState}. All the arithmetic lives in
 * {@code com.patipp.scheduling}, and this class only converts between that record and a row —
 * which is what keeps ninety days of scheduling behaviour testable without a database, and what
 * makes swapping the scheduler a change to one pure class rather than to an entity.
 */
@Entity
@Table(name = "learning_states")
public class LearningState extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "phase", nullable = false, length = 12)
    private String phase = ReviewState.Phase.NEW.name();

    @Column(name = "stability", nullable = false)
    private BigDecimal stability = BigDecimal.ONE;

    @Column(name = "difficulty", nullable = false)
    private BigDecimal difficulty = BigDecimal.valueOf(5.0);

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "interval_days", nullable = false)
    private BigDecimal intervalDays = BigDecimal.ZERO;

    @Column(name = "reps", nullable = false)
    private int reps;

    @Column(name = "lapses", nullable = false)
    private int lapses;

    @Column(name = "consecutive_correct", nullable = false)
    private int consecutiveCorrect;

    @Column(name = "consecutive_incorrect", nullable = false)
    private int consecutiveIncorrect;

    @Column(name = "learning_step", nullable = false)
    private int learningStep;

    @Column(name = "total_attempts", nullable = false)
    private int totalAttempts;

    @Column(name = "total_correct", nullable = false)
    private int totalCorrect;

    @Column(name = "algorithm", nullable = false, length = 16)
    private String algorithm;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected LearningState() {
    }

    public static LearningState start(UUID userId, UUID spaceId, UUID questionId,
                                      String authoredDifficulty, String algorithm) {
        LearningState state = new LearningState();
        state.id = UuidV7.generate();
        state.userId = userId;
        state.preparationSpaceId = spaceId;
        state.questionId = questionId;
        state.algorithm = algorithm;
        state.apply(ReviewState.newItem(authoredDifficulty));
        return state;
    }

    /** The pure record the scheduler operates on. */
    public ReviewState toReviewState() {
        return new ReviewState(
                ReviewState.Phase.valueOf(phase),
                stability.doubleValue(),
                difficulty.doubleValue(),
                dueAt,
                lastReviewedAt,
                intervalDays.doubleValue(),
                reps,
                lapses,
                consecutiveCorrect,
                consecutiveIncorrect,
                learningStep);
    }

    /** Writes back whatever the scheduler decided, and counts the attempt. */
    public void record(ReviewState next, boolean correct) {
        apply(next);
        this.totalAttempts++;
        if (correct) {
            this.totalCorrect++;
        }
    }

    private void apply(ReviewState state) {
        this.phase = state.phase().name();
        // Scale 4 to match the column; rounding here rather than letting the driver decide
        // means a rebuild and the incremental path agree to the last digit.
        this.stability = BigDecimal.valueOf(Math.max(0.0001, state.stability()))
                .setScale(4, RoundingMode.HALF_UP);
        this.difficulty = BigDecimal.valueOf(Math.clamp(state.difficulty(), 1.0, 10.0))
                .setScale(2, RoundingMode.HALF_UP);
        // Truncated to what the column can actually hold. Postgres timestamptz stores
        // microseconds, so an untruncated Instant differs from itself after a round trip - which
        // showed up as a rebuild that reproduced every schedule correctly and still failed to
        // compare equal. Rounding here, at the boundary, for the same reason the numerics are.
        this.dueAt = truncate(state.dueAt());
        this.lastReviewedAt = truncate(state.lastReviewedAt());
        this.intervalDays = BigDecimal.valueOf(Math.max(0.0, state.intervalDays()))
                .setScale(4, RoundingMode.HALF_UP);
        this.reps = state.reps();
        this.lapses = state.lapses();
        this.consecutiveCorrect = state.consecutiveCorrect();
        this.consecutiveIncorrect = state.consecutiveIncorrect();
        this.learningStep = state.learningStep();
    }

    private static Instant truncate(Instant value) {
        return value == null ? null : value.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    /**
     * Parks or unparks the item.
     *
     * <p>Suspending keeps the schedule rather than discarding it, so unsuspending resumes where
     * the learner left off instead of starting over.
     */
    public void suspend(boolean suspended) {
        if (suspended) {
            this.phase = ReviewState.Phase.SUSPENDED.name();
        } else if (ReviewState.Phase.valueOf(phase) == ReviewState.Phase.SUSPENDED) {
            this.phase = reps > 0
                    ? ReviewState.Phase.REVIEW.name()
                    : ReviewState.Phase.NEW.name();
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID id() {
        return id;
    }

    public UUID questionId() {
        return questionId;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public double stability() {
        return stability.doubleValue();
    }

    public double difficulty() {
        return difficulty.doubleValue();
    }

    public double intervalDays() {
        return intervalDays.doubleValue();
    }

    public int reps() {
        return reps;
    }

    public int lapses() {
        return lapses;
    }

    public int totalAttempts() {
        return totalAttempts;
    }

    public int totalCorrect() {
        return totalCorrect;
    }

    public ReviewState.Phase phase() {
        return ReviewState.Phase.valueOf(phase);
    }

    public String algorithm() {
        return algorithm;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
