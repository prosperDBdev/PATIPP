package com.patipp.scheduling;

/**
 * Turns an ordinary quiz answer into a recall grade.
 *
 * <p>Flashcards and coding problems report Again/Hard/Good/Easy directly. Nothing else does —
 * and without this, they would not reach the scheduler at all, leaving two disconnected
 * systems where reviewing a flashcard counted towards retention and answering the same fact as
 * a multiple-choice question did not.
 *
 * <p>Response time is the signal that does the real work here. Correctness alone cannot tell
 * "I knew it" from "I worked it out" from "I guessed and got lucky", and <em>a lucky guess
 * scheduled as solid knowledge is precisely how spaced repetition fails people</em>: the item
 * disappears for three weeks on the strength of a coin flip. Taking twice as long as expected
 * is treated as HARD for that reason, even when the answer was right.
 */
public final class GradeDerivation {

    /** Slower than this multiple of the expected time counts as a struggle. */
    static final double SLOW_MULTIPLE = 2.0;

    /** Faster than this, with no hint of doubt, counts as instant recall. */
    static final double FAST_MULTIPLE = 0.5;

    /** Self-reported confidence at or below this means HARD however fast it was. */
    static final int LOW_CONFIDENCE = 2;

    /** Below this many samples, the authored estimate is better evidence than the mean. */
    static final int ENOUGH_SAMPLES = 8;

    private GradeDerivation() {
    }

    /**
     * @param correct          whether it was marked right
     * @param responseTimeMs   how long it took, or null when the client did not measure it
     * @param confidence       optional 1-4 self-report
     * @param expectedSeconds  the question's authored estimate
     * @param averageMs        the observed mean across everyone, or null
     * @param sampleCount      how many responses that mean is built from
     */
    public static Grade derive(boolean correct, Integer responseTimeMs, Integer confidence,
                               int expectedSeconds, Integer averageMs, int sampleCount) {
        if (!correct) {
            return Grade.AGAIN;
        }

        double expectedMs = expectedMs(expectedSeconds, averageMs, sampleCount);

        // Doubt reported by the learner outranks a fast answer. Someone who says they were
        // unsure was unsure, whatever the clock said.
        if (confidence != null && confidence <= LOW_CONFIDENCE) {
            return Grade.HARD;
        }

        if (responseTimeMs == null) {
            // No timing at all. GOOD rather than EASY: absent evidence is not evidence of
            // mastery, and over-scheduling is the more costly error.
            return Grade.GOOD;
        }

        if (responseTimeMs > expectedMs * SLOW_MULTIPLE) {
            return Grade.HARD;
        }

        boolean confident = confidence == null || confidence == 4;
        if (responseTimeMs < expectedMs * FAST_MULTIPLE && confident) {
            return Grade.EASY;
        }

        return Grade.GOOD;
    }

    /**
     * How long this question should take.
     *
     * <p>The author's estimate to begin with, replaced by the observed mean once there is
     * enough of it. The observed number is better - authors are reliably optimistic - but
     * three samples is not a mean, and letting one slow answer redefine "normal" would make
     * the next learner's grade depend on a stranger's bad morning.
     */
    static double expectedMs(int expectedSeconds, Integer averageMs, int sampleCount) {
        double authored = Math.max(1_000.0, expectedSeconds * 1000.0);
        if (averageMs == null || sampleCount < ENOUGH_SAMPLES || averageMs <= 0) {
            return authored;
        }
        return averageMs;
    }
}
