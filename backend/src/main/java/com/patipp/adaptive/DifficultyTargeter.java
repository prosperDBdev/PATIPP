package com.patipp.adaptive;

import com.patipp.adaptive.LearnerModel.RecentWindow;

/**
 * Decides how hard the next question should be.
 *
 * <p>The aim is a success rate around 78%: high enough to stay worth opening, low enough that
 * you are meeting real resistance. Both failure modes are worse than they sound. Too easy and
 * nothing is learned while the score flatters you; too hard and you stop, which costs more
 * than any amount of inefficient practice.
 *
 * <p>The output is a target <em>rating</em>, not a difficulty label. At a 78% target that is
 * about 220 points below the learner, and the selector scores candidates by how near they
 * land to it.
 */
public final class DifficultyTargeter {

    public static final String VERSION = "TARGET_V1";

    /** The default target success rate. Blueprints may override it. */
    public static final double DEFAULT_TARGET = 0.78;

    /** An easier target while the learner is struggling, so a bad run can end. */
    public static final double RECOVERY_TARGET = 0.90;

    /** How many consecutive answers the recovery target is held for once it engages. */
    public static final int RECOVERY_HOLD = 3;

    /** How far the target may drift from the learner's rating within one session. */
    static final double MAX_SESSION_DRIFT = 250.0;

    static final double STEP_UP = 40.0;
    static final double STEP_DOWN = 60.0;

    public String version() {
        return VERSION;
    }

    /**
     * The rating to aim at for the next question.
     *
     * @param blueprintTarget the space's own target success rate, or null for the default
     */
    public Target targetFor(LearnerModel model, LearnerModel.TopicKey topic,
                            Double blueprintTarget) {
        double ability = model.abilityIn(topic);
        RecentWindow recent = model.recent();

        if (inRecovery(recent)) {
            // Deliberately not a smaller nudge. A learner who has just got four of five wrong
            // needs a question they can actually answer, not one that is nine points easier.
            return new Target(
                    Elo.ratingForExpectation(ability, RECOVERY_TARGET),
                    RECOVERY_TARGET,
                    true,
                    "Recovering after a difficult run");
        }

        double target = blueprintTarget == null ? DEFAULT_TARGET
                : Math.clamp(blueprintTarget, 0.5, 0.95);
        double rating = Elo.ratingForExpectation(ability, target);

        // Three clean wins means the estimate is lagging behind; two losses means it is
        // ahead. Down faster than up, because being over-faced is the more costly error.
        int correctStreak = recent.currentCorrectStreak();
        int wrongStreak = recent.currentWrongStreak();

        String note = "Aiming for a %d%% success rate".formatted(Math.round(target * 100));
        if (correctStreak >= 3) {
            rating += STEP_UP;
            note = "Stepping up after %d in a row".formatted(correctStreak);
        } else if (wrongStreak >= 2) {
            rating -= STEP_DOWN;
            note = "Easing off after %d wrong".formatted(wrongStreak);
        }

        // One bad night must not undo a month of calibration, so the target stays within a
        // band around the learner's measured ability however the session goes.
        double floor = ability - Elo.SCALE - MAX_SESSION_DRIFT;
        double ceiling = ability + MAX_SESSION_DRIFT;

        return new Target(Math.clamp(rating, floor, ceiling), target, false, note);
    }

    /**
     * True when at most one of the last five was right.
     *
     * <p>This is the "do not make the system frustrating" requirement written as a rule
     * rather than an intention.
     */
    static boolean inRecovery(RecentWindow recent) {
        return recent.size() >= 5 && recent.correctInLast(5) <= 1;
    }

    /**
     * @param rating       the item rating to aim for
     * @param successRate  the success probability that rating represents
     * @param recovery     true when the easier target is in force
     * @param explanation  why, in words the learner could be shown
     */
    public record Target(double rating, double successRate, boolean recovery,
                         String explanation) {
    }
}
