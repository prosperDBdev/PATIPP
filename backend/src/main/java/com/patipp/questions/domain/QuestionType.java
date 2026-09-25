package com.patipp.questions.domain;

/**
 * The question formats the platform understands.
 *
 * <p>Values are persisted as varchar with a CHECK constraint rather than a Postgres enum,
 * so adding a format later is a migration that alters a constraint rather than one that
 * rewrites a type used by several tables.
 *
 * <p>Only the first five are implemented in Phase 2. The rest are declared because
 * preparation-type blueprints already reference them, and a blueprint naming a format the
 * code has not reached yet must be readable rather than an error.
 */
public enum QuestionType {

    MCQ(true),
    MULTI_SELECT(true),
    TRUE_FALSE(true),
    SHORT_ANSWER(true),
    FLASHCARD(true),

    // Phase 5.5. None of them execute code: output prediction is auto-graded by comparing
    // strings, and the other two are graded by the learner against the author's rubric.
    CODING(true),
    DEBUGGING(true),
    OUTPUT_PREDICTION(true),

    LONG_ANSWER(false),
    SCENARIO(false),
    BEHAVIORAL(false);

    private final boolean implemented;

    QuestionType(boolean implemented) {
        this.implemented = implemented;
    }

    /** True when this phase can validate, store and evaluate the format. */
    public boolean isImplemented() {
        return implemented;
    }

    /** Default authoring estimate, refined per question and later by real response times. */
    public int defaultEstimatedSeconds() {
        return switch (this) {
            case TRUE_FALSE, FLASHCARD -> 20;
            case MCQ -> 60;
            case MULTI_SELECT -> 90;
            case SHORT_ANSWER -> 90;
            case OUTPUT_PREDICTION -> 120;
            case LONG_ANSWER, SCENARIO, BEHAVIORAL -> 240;
            case DEBUGGING -> 600;
            case CODING -> 1200;
        };
    }
}
