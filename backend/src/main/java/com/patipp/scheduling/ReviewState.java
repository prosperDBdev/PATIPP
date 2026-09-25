package com.patipp.scheduling;

import java.time.Duration;
import java.time.Instant;

/**
 * Where one question stands in one learner's review schedule.
 *
 * <p>A plain record with no identity of its own: the scheduler is a function from a state and
 * a grade to a new state, which is what makes ninety days of study history testable by
 * advancing a clock in a loop rather than by running a database for a quarter.
 *
 * @param stability  days until the chance of recall falls to about 90%
 * @param difficulty how hard this item is for <em>this</em> learner, 1 to 10
 * @param dueAt      when it should next be seen; null for an item never studied
 * @param reps       successful reviews so far
 * @param lapses     times it has been forgotten after reaching REVIEW
 */
public record ReviewState(
        Phase phase,
        double stability,
        double difficulty,
        Instant dueAt,
        Instant lastReviewedAt,
        double intervalDays,
        int reps,
        int lapses,
        int consecutiveCorrect,
        int consecutiveIncorrect,
        int learningStep) {

    public enum Phase {
        /** Never studied. */
        NEW,
        /** Being learned: short fixed steps, not yet on the stability curve. */
        LEARNING,
        /** On the curve. Intervals grow with each success. */
        REVIEW,
        /** Forgotten after reaching REVIEW, and working back up through the steps. */
        RELEARNING,
        /** Parked by the learner. Never served, never counted as overdue. */
        SUSPENDED
    }

    /** Where every item starts, with difficulty seeded from the authored label. */
    public static ReviewState newItem(String authoredDifficulty) {
        return new ReviewState(
                Phase.NEW,
                1.0,
                initialDifficulty(authoredDifficulty),
                null,
                null,
                0.0,
                0, 0, 0, 0, 0);
    }

    /**
     * A harder label starts harder, so the first interval is not wildly optimistic.
     *
     * <p>A prior only. Real grades move it from the first review, so a mislabelled question
     * corrects itself the same way its Elo rating does.
     */
    static double initialDifficulty(String authoredDifficulty) {
        return switch (authoredDifficulty == null ? "" : authoredDifficulty.toUpperCase()) {
            case "EASY" -> 4.2;
            case "HARD" -> 5.8;
            case "EXPERT" -> 6.6;
            default -> 5.0;
        };
    }

    /**
     * The chance of recalling this right now, 0 to 1.
     *
     * <p>{@code 0.9 ^ (days / stability)} - by definition 0.9 exactly one stability-length
     * interval after the last review, which is what makes stability readable as "days until I
     * would probably have forgotten it".
     */
    public double retrievability(Instant now) {
        if (lastReviewedAt == null || stability <= 0) {
            return 0.0;
        }
        double days = Math.max(0.0, daysBetween(lastReviewedAt, now));
        return Math.pow(0.9, days / stability);
    }

    public boolean isDue(Instant now) {
        return phase != Phase.SUSPENDED && dueAt != null && !dueAt.isAfter(now);
    }

    /** Days past due, or 0 when it is not due yet. */
    public double overdueDays(Instant now) {
        if (!isDue(now)) {
            return 0.0;
        }
        return Math.max(0.0, daysBetween(dueAt, now));
    }

    public boolean isNew() {
        return phase == Phase.NEW;
    }

    static double daysBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 86_400_000.0;
    }
}
