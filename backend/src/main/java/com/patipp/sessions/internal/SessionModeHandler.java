package com.patipp.sessions.internal;

import com.patipp.sessions.domain.SessionMode;
import com.patipp.sessions.domain.StudySession;
import java.time.Instant;
import java.util.Map;

/**
 * The behaviour that genuinely differs between kinds of session.
 *
 * <p>Everything a session does the same way - serving items, recording an attempt, counting
 * answers, computing a score - lives once in the service. Only the handful of decisions that
 * actually vary are delegated here.
 *
 * <p>The list is deliberately short, and staying short is the test of the design. Adding exam
 * mode in Phase 4 added exactly two methods to this interface and one implementation class;
 * it needed no new attempt log, no second scoring path and no parallel history screen. If a
 * future mode seems to need those, the shared engine has grown an assumption that belongs in
 * a handler.
 */
public interface SessionModeHandler {

    SessionMode mode();

    /**
     * When this session must end, or null if it has no deadline.
     *
     * <p>Server-computed on purpose. Practice returns null; exams return a real instant, and a
     * client that claims otherwise is ignored - a timer the browser controls is a timer that
     * makes your own scores meaningless.
     *
     * <p>Takes the merged blueprint as well as the request, so a certification space can
     * default to 130 minutes and an academic exam space to 60 without either number being
     * written into a handler.
     */
    Instant deadlineFor(Map<String, Object> config, Map<String, Object> effectiveSettings,
                        Instant startedAt);

    /**
     * Whether the learner sees whether they were right straight after answering.
     *
     * <p>True for practice, because being told immediately is how you learn from a mistake.
     * False for exams, because knowing how question three went changes how you answer the
     * rest of the paper.
     */
    boolean revealsFeedbackImmediately(StudySession session);

    /** How many questions to serve, given the request and the space's blueprint defaults. */
    int resolveLength(Map<String, Object> config, Map<String, Object> effectiveSettings);

    /**
     * Whether the learner may move around freely, rather than being served one question at a
     * time in order.
     *
     * <p>False for practice: it moves forward, and there is nothing to come back to. True for
     * exams, where skipping a hard question and returning to it is a real exam skill and part
     * of what a mock is meant to rehearse.
     */
    default boolean allowsFreeNavigation() {
        return false;
    }

    /**
     * Whether an answer may be changed while the session is still open.
     *
     * <p>False for practice: it is marked the moment you commit, and the explanation is
     * already on screen, so a second answer would only be copying it back.
     *
     * <p>True for exams, where changing your mind on the way back through the paper is the
     * whole reason for flagging a question in the first place. The earlier attempt is not
     * edited - the log is append-only, so a revision is a new attempt and both remain
     * visible. Only the latest one counts towards the score.
     */
    default boolean allowsAnswerRevision() {
        return false;
    }

    /**
     * Whether selection should follow the curriculum's subject weights.
     *
     * <p>False for practice, which draws evenly from whatever matches the filters. True for
     * exams: if React is thirty percent of the real paper, a mock that is ten percent React
     * is not measuring what it claims to.
     */
    default boolean weightsBySubject() {
        return false;
    }
}
