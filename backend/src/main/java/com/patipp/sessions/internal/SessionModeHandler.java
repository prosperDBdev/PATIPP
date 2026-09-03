package com.patipp.sessions.internal;

import com.patipp.sessions.domain.SessionMode;
import com.patipp.sessions.domain.StudySession;
import java.time.Instant;
import java.util.Map;

/**
 * The behaviour that genuinely differs between kinds of session.
 *
 * <p>Everything a session does the same way - serving items in order, recording an attempt,
 * counting answers, computing a score - lives once in the service. Only the handful of
 * decisions that actually vary are delegated here.
 *
 * <p>The list is deliberately short, and staying short is the test of the design. Exam mode
 * in Phase 4 is a deadline, no immediate feedback and blueprint-weighted selection. Interview
 * mode is conversational turns. Neither needs its own attempt log, scoring path or history
 * screen; if a future mode seems to, the shared engine has probably grown an assumption that
 * belongs in a handler.
 */
public interface SessionModeHandler {

    SessionMode mode();

    /**
     * When this session must end, or null if it has no deadline.
     *
     * <p>Server-computed on purpose. Practice returns null; exams return a real instant, and a
     * client that says otherwise is ignored - a timer the browser controls is a timer that
     * makes your own scores meaningless.
     */
    Instant deadlineFor(Map<String, Object> config, Instant startedAt);

    /**
     * Whether the learner sees whether they were right straight after answering.
     *
     * <p>True for practice, because being told immediately is how you learn from a mistake.
     * False for exams, because knowing mid-paper changes how you answer the rest.
     */
    boolean revealsFeedbackImmediately(StudySession session);

    /** How many questions to serve, given the request and the space's blueprint defaults. */
    int resolveLength(Map<String, Object> config, Map<String, Object> effectiveSettings);
}
