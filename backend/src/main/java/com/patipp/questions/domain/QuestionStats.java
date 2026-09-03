package com.patipp.questions.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * How hard a question turned out to be, aggregated over everyone who answered it.
 *
 * <p>This is item data, not learner data, so it legitimately belongs to the question rather
 * than to a user. It sits in its own table for two reasons: to keep the heavy write traffic
 * of Phase 5 off the {@code questions} row, and to keep the content layer free of anything
 * derived, so a question bank can still be exported as pure content.
 *
 * <p>Seeded here from the authored difficulty and corrected by real responses from Phase 5.
 * That is what lets a question you mislabelled as EASY quietly repair itself instead of
 * misleading the selector forever.
 */
@Entity
@Table(name = "question_stats")
public class QuestionStats extends com.patipp.common.jpa.AssignedIdEntity<UUID> {

    @Id
    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "elo_rating", nullable = false, precision = 7, scale = 2)
    private BigDecimal eloRating;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "times_served", nullable = false)
    private int timesServed;

    @Column(name = "times_correct", nullable = false)
    private int timesCorrect;

    @Column(name = "avg_response_ms")
    private Integer avgResponseMs;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected QuestionStats() {
        // for JPA
    }

    public static QuestionStats seedFor(Question question) {
        QuestionStats stats = new QuestionStats();
        stats.questionId = question.id();
        stats.preparationSpaceId = question.preparationSpaceId();
        stats.eloRating = BigDecimal.valueOf(question.difficulty().seedRating());
        return stats;
    }

    public UUID questionId() {
        return questionId;
    }

    /**
     * Required by Persistable. The primary key here is the question id: statistics are one
     * row per question, so giving them a surrogate key of their own would add nothing.
     */
    @Override
    public UUID getId() {
        return questionId;
    }

    public BigDecimal eloRating() {
        return eloRating;
    }

    public int ratingCount() {
        return ratingCount;
    }

    public int timesServed() {
        return timesServed;
    }

    public int timesCorrect() {
        return timesCorrect;
    }

    public Integer avgResponseMs() {
        return avgResponseMs;
    }

    /**
     * Resets the rating to the prior implied by a new difficulty label.
     *
     * <p>Only applied while the item has too few responses to have learned anything of its
     * own; past that point the observed rating is better evidence than the author's guess,
     * and overwriting it would throw away real data in favour of an opinion.
     */
    public void reseedFrom(Difficulty difficulty) {
        if (ratingCount < 10) {
            this.eloRating = BigDecimal.valueOf(difficulty.seedRating());
        }
    }
}
