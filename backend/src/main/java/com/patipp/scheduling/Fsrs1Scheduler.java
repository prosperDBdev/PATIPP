package com.patipp.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Stability and difficulty, in the spirit of FSRS but with fixed coefficients.
 *
 * <p>Full FSRS-5 fits seventeen parameters against a large review history. On day one there is
 * no history to fit them to, so this uses sensible constants instead and keeps the same shape,
 * which is what makes the eventual swap a one-class change behind
 * {@link ReviewScheduler}.
 *
 * <p>The one piece that matters most is the spacing term in {@link #grow}: recalling something
 * you had <em>almost</em> forgotten strengthens it far more than recalling something reviewed
 * an hour ago. Without it, a scheduler quietly rewards cramming, which is the opposite of what
 * it exists for.
 */
public final class Fsrs1Scheduler implements ReviewScheduler {

    public static final String VERSION = "FSRS_V1";

    /** How far one grade moves difficulty. GOOD leaves it alone by construction. */
    private static final double DIFFICULTY_STEP = 0.55;

    /** Base growth factor, reduced as an item proves difficult for this learner. */
    private static final double GROWTH_BASE = 2.6;
    private static final double GROWTH_PER_DIFFICULTY = 0.16;

    /** How much of the growth the spacing effect can add. */
    private static final double SPACING_WEIGHT = 0.4;

    /** What a lapse leaves behind, before the difficulty penalty. */
    private static final double LAPSE_RETENTION = 0.42;
    private static final double LAPSE_PER_DIFFICULTY = 0.045;
    private static final double MINIMUM_STABILITY = 0.4;

    /** Overdue by more than this multiple of its own interval is HIGH priority. */
    private static final double SEVERELY_OVERDUE = 1.0;

    /** Below this many reps an item is not yet established. */
    private static final int ESTABLISHED_REPS = 4;

    /** Above this stability, and consistently right, an item can safely wait. */
    private static final double COMFORTABLE_STABILITY_DAYS = 21.0;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ReviewState next(ReviewState current, Grade grade, Instant now,
                            SchedulerConfig config) {
        if (current.phase() == ReviewState.Phase.SUSPENDED) {
            // A parked item that somehow gets answered is recorded but not rescheduled;
            // un-suspending is an explicit act by the learner, not a side effect.
            return current;
        }

        double retrievability = current.retrievability(now);
        double difficulty = nextDifficulty(current.difficulty(), grade);

        return switch (current.phase()) {
            case NEW, LEARNING, RELEARNING ->
                    throughLearningSteps(current, grade, now, config, difficulty);
            case REVIEW -> grade.isLapse()
                    ? lapse(current, now, config, difficulty)
                    : review(current, grade, now, config, difficulty, retrievability);
            case SUSPENDED -> current;
        };
    }

    /**
     * Difficulty drifts towards whatever the grades say, and GOOD is the fixed point.
     *
     * <p>{@code 3 - grade} is zero for GOOD, negative for EASY, positive for HARD and AGAIN.
     * So an item answered correctly-with-effort creeps harder over time and one answered
     * instantly creeps easier, which is the behaviour a learner would expect if asked.
     */
    static double nextDifficulty(double current, Grade grade) {
        return Math.clamp(current + DIFFICULTY_STEP * (3 - grade.value()), 1.0, 10.0);
    }

    /**
     * The short steps a new or relapsed item walks through before joining the curve.
     *
     * <p>AGAIN sends it back to the first step rather than merely holding position: the point
     * of the steps is to see the item again within the same sitting, and an item you have just
     * failed is the one that most needs that.
     */
    private ReviewState throughLearningSteps(ReviewState current, Grade grade, Instant now,
                                             SchedulerConfig config, double difficulty) {
        int[] steps = config.learningStepMinutes();

        if (grade.isLapse()) {
            return new ReviewState(
                    relearningPhaseFor(current),
                    Math.max(MINIMUM_STABILITY, current.stability()),
                    difficulty,
                    now.plus(Duration.ofMinutes(steps[0])),
                    now,
                    steps[0] / 1440.0,
                    current.reps(),
                    // Failing something you have answered before is a lapse even if it never
                    // graduated. Counting only post-REVIEW failures meant an item answered
                    // right-then-wrong-then-right forever recorded zero lapses, so it never
                    // reached HIGH priority — leaving the item most in need of attention sitting
                    // in the middle of the queue.
                    current.lastReviewedAt() == null ? current.lapses() : current.lapses() + 1,
                    0,
                    current.consecutiveIncorrect() + 1,
                    0);
        }

        // EASY skips the remaining steps: there is nothing to be learned from showing
        // something again in ten minutes that was answered instantly.
        int step = grade == Grade.EASY ? steps.length : current.learningStep() + 1;

        if (step >= steps.length) {
            double stability = graduatingStability(grade, difficulty);
            double interval = intervalFor(stability, config);
            return new ReviewState(
                    ReviewState.Phase.REVIEW,
                    stability,
                    difficulty,
                    now.plus(Duration.ofMinutes(Math.round(interval * 1440))),
                    now,
                    interval,
                    current.reps() + 1,
                    current.lapses(),
                    current.consecutiveCorrect() + 1,
                    0,
                    0);
        }

        return new ReviewState(
                ReviewState.Phase.LEARNING,
                current.stability(),
                difficulty,
                now.plus(Duration.ofMinutes(steps[step])),
                now,
                steps[step] / 1440.0,
                current.reps(),
                current.lapses(),
                current.consecutiveCorrect() + 1,
                0,
                step);
    }

    /** An item that has never reached REVIEW cannot relapse; it is simply still learning. */
    private static ReviewState.Phase relearningPhaseFor(ReviewState current) {
        return current.phase() == ReviewState.Phase.NEW
                ? ReviewState.Phase.LEARNING
                : current.phase();
    }

    /** How stable an item is when it first leaves the learning steps. */
    private static double graduatingStability(Grade grade, double difficulty) {
        double base = switch (grade) {
            case HARD -> 1.2;
            case EASY -> 4.0;
            default -> 2.5;
        };
        // A hard item graduates less stable, so its first real interval is shorter.
        return Math.max(MINIMUM_STABILITY, base * (1.0 - 0.04 * (difficulty - 5.0)));
    }

    private ReviewState review(ReviewState current, Grade grade, Instant now,
                               SchedulerConfig config, double difficulty,
                               double retrievability) {
        double stability = grow(current.stability(), difficulty, grade, retrievability);
        double interval = intervalFor(stability, config);

        return new ReviewState(
                ReviewState.Phase.REVIEW,
                stability,
                difficulty,
                now.plus(Duration.ofMinutes(Math.round(interval * 1440))),
                now,
                interval,
                current.reps() + 1,
                current.lapses(),
                current.consecutiveCorrect() + 1,
                0,
                0);
    }

    /**
     * How much the growth slows as an item becomes well known.
     *
     * <p>A deviation from the formula in ADAPTIVE-ENGINE.md, and a necessary one. That formula
     * multiplies stability by a constant factor on every success, so at the default settings
     * each review nearly triples the interval and an item reaches the one-year ceiling after
     * seven reviews. Nothing about seven correct answers justifies not asking again for a year.
     * Real FSRS damps growth by a negative power of stability for exactly this reason, and this
     * is the same idea with one fixed exponent.
     */
    private static final double SATURATION_EXPONENT = -0.15;

    /**
     * Stability after a success.
     *
     * <p>Four things scale the growth: the grade, how difficult the item has proved, how close
     * the learner was to forgetting it, and how well established it already is. The third is
     * the spacing effect — why an item recalled at 60% retrievability gains far more than the
     * same item recalled an hour after the last look. The fourth stops the whole thing running
     * away, so intervals decelerate towards the ceiling instead of slamming into it.
     */
    static double grow(double stability, double difficulty, Grade grade, double retrievability) {
        double gradeMultiplier = switch (grade) {
            case HARD -> 0.55;
            case EASY -> 1.35;
            default -> 1.0;
        };
        double difficultyFactor = Math.max(0.1, GROWTH_BASE - GROWTH_PER_DIFFICULTY * difficulty);
        double spacing = 1.0 + SPACING_WEIGHT * (1.0 - Math.clamp(retrievability, 0.0, 1.0));
        double saturation = Math.pow(Math.max(0.1, stability), SATURATION_EXPONENT);

        return stability * (1.0 + difficultyFactor * gradeMultiplier * spacing * saturation);
    }

    /** Stability after forgetting: collapsed, and further for an item already known to be hard. */
    private ReviewState lapse(ReviewState current, Instant now, SchedulerConfig config,
                              double difficulty) {
        double stability = Math.max(MINIMUM_STABILITY,
                current.stability() * LAPSE_RETENTION * (1.0 - LAPSE_PER_DIFFICULTY * difficulty));
        int[] steps = config.learningStepMinutes();

        return new ReviewState(
                ReviewState.Phase.RELEARNING,
                stability,
                difficulty,
                now.plus(Duration.ofMinutes(steps[0])),
                now,
                steps[0] / 1440.0,
                current.reps(),
                current.lapses() + 1,
                0,
                current.consecutiveIncorrect() + 1,
                0);
    }

    /**
     * Days until recall probability reaches the target.
     *
     * <p>{@code S * ln(r) / ln(0.9)}, which is exactly {@code S} at the default 0.90 target.
     * Asking for higher retention shortens every interval, which is the honest trade: more
     * reviews for less forgetting.
     */
    static double intervalFor(double stability, SchedulerConfig config) {
        double interval = stability * Math.log(config.targetRetention()) / Math.log(0.9);
        return Math.clamp(interval, 1.0 / 1440.0, config.maximumIntervalDays());
    }

    @Override
    public Priority priority(ReviewState state, Instant now) {
        if (state.phase() == ReviewState.Phase.SUSPENDED) {
            return Priority.SUSPENDED;
        }

        // Repeatedly forgotten, or so overdue that the scheduled interval no longer describes
        // anything. Both mean the item is not actually known.
        if (state.lapses() >= 2
                || state.consecutiveIncorrect() >= 2
                || state.overdueDays(now) > Math.max(1.0, state.intervalDays()) * SEVERELY_OVERDUE) {
            return Priority.HIGH;
        }

        if (state.reps() >= ESTABLISHED_REPS
                && state.consecutiveCorrect() >= 3
                && state.stability() > COMFORTABLE_STABILITY_DAYS) {
            return Priority.LOW;
        }

        return Priority.MEDIUM;
    }

    @Override
    public Map<Grade, Double> previewIntervals(ReviewState current, Instant now,
                                               SchedulerConfig config) {
        Map<Grade, Double> preview = new EnumMap<>(Grade.class);
        for (Grade grade : Grade.values()) {
            preview.put(grade, next(current, grade, now, config).intervalDays());
        }
        return preview;
    }
}
