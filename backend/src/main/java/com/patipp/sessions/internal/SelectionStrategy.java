package com.patipp.sessions.internal;

/**
 * How a mode picks its questions.
 *
 * <p>An enum rather than a third boolean on {@link SessionModeHandler}. Two booleans were
 * already at the edge of what that shape can carry honestly; a third would allow
 * combinations that mean nothing - adaptive and blueprint-weighted at once - and leave the
 * engine to decide which wins. One question with one answer cannot be self-contradictory.
 */
public enum SelectionStrategy {

    /** Random within the filters. The behaviour of Phases 1 to 3, still the safe default. */
    RANDOM,

    /**
     * Weighted to the curriculum, at authored difficulty.
     *
     * <p>Exams. A mock that adapted would be a different paper each time and could not be
     * compared with the one you sat a fortnight ago, which is the only thing a mock is for.
     */
    BLUEPRINT_WEIGHTED,

    /**
     * The adaptive engine: weakness, difficulty fit, coverage and retention.
     *
     * <p>Practice, where the point is to work on what you are actually bad at.
     */
    ADAPTIVE
}
