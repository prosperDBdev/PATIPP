package com.patipp.questions.domain;

/**
 * The authoring difficulty vocabulary.
 *
 * <p>From Phase 5 the engine works in a continuous rating and treats this label only as a
 * starting estimate, which is why {@link #seedRating()} exists: real responses correct the
 * rating, so a question you mislabelled repairs itself rather than misleading the selector
 * forever.
 */
public enum Difficulty {

    EASY(1000),
    MEDIUM(1200),
    HARD(1400),
    EXPERT(1600);

    private final int seedRating;

    Difficulty(int seedRating) {
        this.seedRating = seedRating;
    }

    /** Initial item rating, used as a prior until enough responses exist to move it. */
    public int seedRating() {
        return seedRating;
    }
}
