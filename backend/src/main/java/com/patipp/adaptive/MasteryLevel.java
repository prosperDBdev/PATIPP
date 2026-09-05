package com.patipp.adaptive;

import com.patipp.adaptive.LearnerModel.TopicState;

/**
 * How well a bucket of the syllabus is known.
 *
 * <p>{@link #UNASSESSED} is the important one and is not a polite way of saying "weak". A
 * topic with four answers behind it has not been measured, and calling it weak produces a
 * study plan built on noise. The first time such a recommendation is obviously wrong, the
 * learner stops believing the rest of them - so the engine says "needs assessment" instead,
 * which is both honest and a genuinely different instruction.
 */
public enum MasteryLevel {

    /** Never attempted. */
    UNTOUCHED,

    /** Attempted, but too few times to draw a conclusion from. */
    UNASSESSED,

    WEAK,
    DEVELOPING,
    PROFICIENT,
    STRONG;

    /**
     * Classifies from recency-weighted accuracy, once there is enough of it.
     *
     * <p>Decayed rather than lifetime accuracy on purpose: a topic answered perfectly in
     * March and not touched since is not {@code STRONG} today, and a learner who has just
     * turned a weak topic around should see that reflected within a session or two.
     */
    public static MasteryLevel classify(int attempts, double decayedAccuracy) {
        if (attempts == 0) {
            return UNTOUCHED;
        }
        if (attempts < TopicState.CONFIDENCE_FLOOR) {
            return UNASSESSED;
        }
        if (decayedAccuracy < 0.55) {
            return WEAK;
        }
        if (decayedAccuracy < 0.72) {
            return DEVELOPING;
        }
        return decayedAccuracy < 0.88 ? PROFICIENT : STRONG;
    }

    /** True when the level reflects measured performance rather than a lack of data. */
    public boolean isMeasured() {
        return this != UNTOUCHED && this != UNASSESSED;
    }
}
