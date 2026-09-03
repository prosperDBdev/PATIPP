package com.patipp.sessions.domain;

/**
 * IN_PROGRESS is resumable. SUBMITTED is finished and scored. ABANDONED is one the user
 * walked away from and chose not to resume. EXPIRED is for Phase 4, where a deadline passes
 * without a submission.
 *
 * <p>None of these are ever deleted. A half-finished session is still evidence of what was
 * studied, and the attempts recorded inside it already count towards mastery.
 */
public enum SessionStatus {
    IN_PROGRESS,
    SUBMITTED,
    ABANDONED,
    EXPIRED;

    public boolean isFinished() {
        return this != IN_PROGRESS;
    }
}
