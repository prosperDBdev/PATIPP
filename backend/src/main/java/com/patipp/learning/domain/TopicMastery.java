package com.patipp.learning.domain;

import com.patipp.adaptive.MasteryLevel;
import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * What one learner has done in one bucket of one space's syllabus.
 *
 * <p>Derived state. Every field here can be recomputed by replaying
 * {@code question_attempts}, and {@code MasteryRebuilder} does exactly that. Nothing else in
 * the application may treat this table as a source of truth - which is precisely what makes
 * it safe to change the algorithm later and rebuild.
 */
@Entity
@Table(name = "topic_mastery")
public class TopicMastery extends AssignedIdEntity<UUID> {

    /** How fast old answers stop counting. Two weeks is short enough to notice rust. */
    private static final double DECAY_HALF_LIFE_DAYS = 14.0;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    /** Null for the bucket holding this subject's questions that have no topic. */
    @Column(name = "topic_id", updatable = false)
    private UUID topicId;

    @Column(name = "ability", nullable = false)
    private BigDecimal ability = BigDecimal.valueOf(1200.00);

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "correct", nullable = false)
    private int correct;

    @Column(name = "decayed_attempts", nullable = false)
    private BigDecimal decayedAttempts = BigDecimal.ZERO;

    @Column(name = "decayed_correct", nullable = false)
    private BigDecimal decayedCorrect = BigDecimal.ZERO;

    @Column(name = "decayed_at")
    private Instant decayedAt;

    @Column(name = "questions_seen", nullable = false)
    private int questionsSeen;

    @Column(name = "consecutive_correct", nullable = false)
    private int consecutiveCorrect;

    @Column(name = "consecutive_wrong", nullable = false)
    private int consecutiveWrong;

    @Column(name = "total_response_ms", nullable = false)
    private long totalResponseMs;

    @Column(name = "timed_attempts", nullable = false)
    private int timedAttempts;

    @Column(name = "mastery_level", nullable = false, length = 16)
    private String masteryLevel = MasteryLevel.UNTOUCHED.name();

    @Column(name = "last_practiced_at")
    private Instant lastPracticedAt;

    @Column(name = "last_incorrect_at")
    private Instant lastIncorrectAt;

    @Column(name = "algorithm", nullable = false, length = 24)
    private String algorithm;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected TopicMastery() {
    }

    public static TopicMastery start(UUID userId, UUID spaceId, UUID subjectId, UUID topicId,
                                     double startingAbility, String algorithm) {
        TopicMastery mastery = new TopicMastery();
        mastery.id = UuidV7.generate();
        mastery.userId = userId;
        mastery.preparationSpaceId = spaceId;
        mastery.subjectId = subjectId;
        mastery.topicId = topicId;
        mastery.ability = round2(startingAbility);
        mastery.algorithm = algorithm;
        return mastery;
    }

    /**
     * Folds one answer in.
     *
     * @param score          0-1, so partial credit counts partially
     * @param newQuestion    true the first time this learner meets this question here, which
     *                       is what coverage counts - meeting the same question ten times is
     *                       not ten questions' worth of the syllabus
     * @param newAbility     the Elo the estimator produced; this class does not compute it,
     *                       because the algorithm lives in the pure module and must stay
     *                       swappable
     */
    public void record(double score, boolean correct, Integer responseTimeMs,
                       boolean newQuestion, double newAbility, Instant at) {
        decayTo(at);

        this.decayedAttempts = this.decayedAttempts.add(BigDecimal.ONE);
        this.decayedCorrect = this.decayedCorrect
                .add(BigDecimal.valueOf(Math.clamp(score, 0.0, 1.0)))
                // Floating-point drift over thousands of attempts could otherwise push the
                // weighted correct count a hair above the weighted total, which the check
                // constraint would refuse.
                .min(this.decayedAttempts);

        this.attempts++;
        if (correct) {
            this.correct++;
            this.consecutiveCorrect++;
            this.consecutiveWrong = 0;
        } else {
            this.consecutiveWrong++;
            this.consecutiveCorrect = 0;
            this.lastIncorrectAt = at;
        }

        if (newQuestion) {
            this.questionsSeen++;
        }
        if (responseTimeMs != null && responseTimeMs > 0) {
            this.totalResponseMs += responseTimeMs;
            this.timedAttempts++;
        }

        this.ability = round2(newAbility);
        this.lastPracticedAt = at;
        this.masteryLevel = MasteryLevel.classify(this.attempts, decayedAccuracy()).name();
    }

    /**
     * Ages the weighted counts up to {@code at}.
     *
     * <p>Applied before every write and before every read, so the numbers are correct at the
     * moment they are used rather than at the moment they were last touched. A learner who
     * stops for a month should come back to a decayed accuracy that has faded, not one frozen
     * where they left it.
     */
    public void decayTo(Instant at) {
        if (decayedAt == null) {
            decayedAt = at;
            return;
        }
        if (!at.isAfter(decayedAt)) {
            return;
        }

        double days = Duration.between(decayedAt, at).toMillis() / 86_400_000.0;
        BigDecimal factor = BigDecimal.valueOf(Math.pow(0.5, days / DECAY_HALF_LIFE_DAYS));

        this.decayedAttempts = this.decayedAttempts.multiply(factor)
                .setScale(6, RoundingMode.HALF_UP);
        this.decayedCorrect = this.decayedCorrect.multiply(factor)
                .setScale(6, RoundingMode.HALF_UP)
                .min(this.decayedAttempts);
        this.decayedAt = at;
    }

    /**
     * Recency-weighted correct rate.
     *
     * <p>The honest measure, and the one every downstream rule reads. Lifetime accuracy tells
     * you what you knew in March: it barely moves after a few hundred attempts and cannot
     * detect that you have gone rusty, which is the single thing "am I ready?" is asking.
     */
    public double decayedAccuracy() {
        if (decayedAttempts.signum() <= 0) {
            return 0.0;
        }
        return decayedCorrect.doubleValue() / decayedAttempts.doubleValue();
    }

    public double accuracy() {
        return attempts == 0 ? 0.0 : (double) correct / attempts;
    }

    public Integer averageResponseMs() {
        return timedAttempts == 0 ? null : (int) (totalResponseMs / timedAttempts);
    }

    private static BigDecimal round2(double value) {
        return BigDecimal.valueOf(com.patipp.adaptive.Elo.clamp(value))
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID preparationSpaceId() {
        return preparationSpaceId;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public UUID topicId() {
        return topicId;
    }

    public double ability() {
        return ability.doubleValue();
    }

    public int attempts() {
        return attempts;
    }

    public int correct() {
        return correct;
    }

    public int questionsSeen() {
        return questionsSeen;
    }

    public int consecutiveCorrect() {
        return consecutiveCorrect;
    }

    public int consecutiveWrong() {
        return consecutiveWrong;
    }

    public MasteryLevel masteryLevel() {
        return MasteryLevel.valueOf(masteryLevel);
    }

    public Instant lastPracticedAt() {
        return lastPracticedAt;
    }

    public Instant lastIncorrectAt() {
        return lastIncorrectAt;
    }

    public String algorithm() {
        return algorithm;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
