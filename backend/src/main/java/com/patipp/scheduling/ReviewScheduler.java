package com.patipp.scheduling;

import java.time.Instant;
import java.util.Map;

/**
 * Decides when something should come back.
 *
 * <p>An interface with a {@link #version()} because this will be replaced. The model behind
 * {@code FSRS_V1} is a deliberate simplification of FSRS-5, whose seventeen parameters need
 * fitting against a large review history nobody has on day one. Every scheduled interval is
 * stamped with the version that produced it, so when a better model arrives it is always
 * possible to say which algorithm chose a given date - and the whole schedule can be rebuilt
 * from the attempt log rather than migrated by guesswork.
 */
public interface ReviewScheduler {

    String version();

    /** Folds one grade into an item's schedule. */
    ReviewState next(ReviewState current, Grade grade, Instant now, SchedulerConfig config);

    /**
     * How urgent this item is, for ordering a review queue.
     *
     * <p>Separate from the interval because "when should I see this" and "what should I do
     * first when I have twenty minutes" are different questions. An item three weeks overdue
     * and one due this morning are both due; only one of them is a problem.
     */
    Priority priority(ReviewState state, Instant now);

    /**
     * What each answer would schedule, before the learner picks one.
     *
     * <p>Shown on the buttons themselves. It turns a self-report from a guess into a decision
     * with visible consequences - "Hard means four days, Good means eleven" - and it is also
     * the fastest way for a learner to notice the scheduler is behaving oddly, which is worth
     * more than any amount of documentation about how it works.
     */
    Map<Grade, Double> previewIntervals(ReviewState current, Instant now, SchedulerConfig config);

    /**
     * @param targetRetention the recall probability to schedule for. Lower means longer
     *                        intervals and more forgetting; higher means more reviews. 0.90 is
     *                        the usual compromise and blueprints may override it.
     * @param learningStepMinutes the short fixed steps a new item passes through before it
     *                        joins the stability curve
     * @param maximumIntervalDays a ceiling, so an item you have known for two years still
     *                        resurfaces occasionally rather than effectively never
     */
    record SchedulerConfig(
            double targetRetention,
            int[] learningStepMinutes,
            double maximumIntervalDays) {

        public static final double DEFAULT_RETENTION = 0.90;

        /** Ten minutes, then a day. Long enough to be a real test, short enough to finish. */
        private static final int[] DEFAULT_STEPS = {10, 1440};

        public static final double DEFAULT_MAXIMUM_DAYS = 365.0;

        public static SchedulerConfig standard() {
            return new SchedulerConfig(DEFAULT_RETENTION, DEFAULT_STEPS, DEFAULT_MAXIMUM_DAYS);
        }

        public SchedulerConfig {
            targetRetention = Math.clamp(targetRetention, 0.70, 0.98);
            learningStepMinutes = learningStepMinutes == null || learningStepMinutes.length == 0
                    ? DEFAULT_STEPS
                    : learningStepMinutes.clone();
            maximumIntervalDays = Math.clamp(maximumIntervalDays, 1.0, 3650.0);
        }

        public int[] learningStepMinutes() {
            return learningStepMinutes.clone();
        }

        public int stepCount() {
            return learningStepMinutes.length;
        }
    }

    /** How soon a due item needs attention relative to the rest of the queue. */
    enum Priority {
        /** Repeatedly forgotten, or so overdue the interval has lost its meaning. */
        HIGH,
        /** Due, and not yet established. */
        MEDIUM,
        /** Due, but well known. Safe to leave if time is short. */
        LOW,
        /** Parked by the learner. */
        SUSPENDED
    }
}
