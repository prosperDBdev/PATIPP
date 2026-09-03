package com.patipp.sessions.domain;

/**
 * Where one served question stands within its session.
 *
 * <p>MARKED_FOR_REVIEW and SKIPPED are unused by practice, which moves forward one question
 * at a time. They are declared now because exam mode in Phase 4 needs both, and adding them
 * later would mean a migration widening a check constraint on a table that by then holds
 * real history.
 */
public enum ItemState {
    UNSEEN,
    VIEWED,
    ANSWERED,
    MARKED_FOR_REVIEW,
    SKIPPED
}
