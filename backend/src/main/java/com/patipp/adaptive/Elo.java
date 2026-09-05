package com.patipp.adaptive;

/**
 * The Elo relation, in one place.
 *
 * <p>Both the learner's ability and each question's difficulty are ratings on the same scale,
 * and every part of the engine that needs "how likely is this person to get this right?"
 * comes through here rather than writing the formula out again.
 *
 * <p>Elo rather than item response theory: a 2PL model needs on the order of a thousand
 * responses per item to fit its parameters, and a personal question bank will have dozens.
 * Elo converges quickly on sparse data, updates in constant time, and can be explained to
 * the person using it - which matters, because an engine nobody understands is an engine
 * nobody trusts.
 */
public final class Elo {

    /** Where a learner starts, and the middle of the authored difficulty ladder. */
    public static final double STARTING_RATING = 1200.0;

    /** The scale constant: 400 points is a ten-to-one odds difference. */
    public static final double SCALE = 400.0;

    public static final double MIN_RATING = 0.0;
    public static final double MAX_RATING = 4000.0;

    private Elo() {
    }

    /**
     * The probability that a learner of this rating answers a question of that rating
     * correctly.
     */
    public static double expectation(double learnerRating, double itemRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (itemRating - learnerRating) / SCALE));
    }

    /**
     * The item rating at which this learner would succeed with probability {@code target}.
     *
     * <p>This is what "aim for 78% success" means numerically: at a target of 0.78 the answer
     * is about 219 points below the learner, so that is the difficulty the selector seeks.
     */
    public static double ratingForExpectation(double learnerRating, double target) {
        double clamped = Math.clamp(target, 0.01, 0.99);
        return learnerRating - SCALE * Math.log10(clamped / (1.0 - clamped));
    }

    /** Keeps a rating inside the range the database column will accept. */
    public static double clamp(double rating) {
        return Math.clamp(rating, MIN_RATING, MAX_RATING);
    }

    /**
     * The rating a question starts at, taken from the difficulty its author chose.
     *
     * <p>A prior, not a verdict. Real responses move it, so a question mislabelled EASY
     * corrects itself after a handful of attempts without anyone noticing it was wrong.
     */
    public static double seedFor(String authoredDifficulty) {
        return switch (authoredDifficulty == null ? "" : authoredDifficulty.toUpperCase()) {
            case "EASY" -> 1000.0;
            case "HARD" -> 1400.0;
            case "EXPERT" -> 1600.0;
            default -> 1200.0;
        };
    }

    /**
     * The difficulty band a question falls into <em>for this learner</em>.
     *
     * <p>The authored ladder stays as an authoring vocabulary and a filter, but the engine
     * works in continuous Elo, and this derives the label for display. The consequence is
     * worth saying plainly: a HARD question becomes a MEDIUM question as you improve, with
     * nobody relabelling anything. Difficulty relative to the learner is the only definition
     * that stays true over six months.
     */
    public static String bandFor(double learnerRating, double itemRating) {
        double relative = itemRating - learnerRating;
        if (relative < -300) {
            return "EASY";
        }
        if (relative < -100) {
            return "MEDIUM";
        }
        return relative <= 150 ? "HARD" : "EXPERT";
    }
}
