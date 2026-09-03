package com.patipp.sessions.domain;

/**
 * How a session behaves.
 *
 * <p>This enum is the reason there is one session engine rather than four. An exam is not a
 * different kind of thing from practice; it is the same thing with a deadline, deferred
 * feedback and a fixed blueprint. Adding a mode means adding a handler, not a parallel copy
 * of scoring, history and analytics.
 *
 * <p>Only PRACTICE is implemented in Phase 3. The rest are declared because preparation-type
 * blueprints already name them, and a blueprint mentioning a mode the code has not reached
 * yet must be readable rather than an error.
 */
public enum SessionMode {

    PRACTICE(true),
    EXAM(false),
    INTERVIEW(false),
    FLASHCARD_REVIEW(false),
    DRILL(false);

    private final boolean implemented;

    SessionMode(boolean implemented) {
        this.implemented = implemented;
    }

    public boolean isImplemented() {
        return implemented;
    }
}
