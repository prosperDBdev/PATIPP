package com.patipp.sessions.api;

import com.patipp.sessions.domain.SessionMode;
import com.patipp.sessions.domain.SessionStatus;
import com.patipp.sessions.domain.StudySession;
import com.patipp.sessions.domain.StudySessionRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the sessions module publishes about finished work.
 *
 * <p>Narrow on purpose. {@code analytics} needs recent mock results to score the readiness
 * component, and nothing more — not the session entity, not the items, not the repositories.
 * Taking a user id as a parameter rather than reading the security context means a nightly job or
 * a rebuild can use it too.
 */
@Component
public class SessionHistoryAccess {

    private final StudySessionRepository sessions;

    public SessionHistoryAccess(StudySessionRepository sessions) {
        this.sessions = sessions;
    }

    /**
     * The most recent finished exams, newest first.
     *
     * <p>Only exams, and only scored ones. A practice run says nothing about performance under
     * exam conditions, which is the entire point of the component this feeds.
     */
    @Transactional(readOnly = true)
    public List<MockResult> recentMocks(UUID userId, UUID spaceId, int limit) {
        List<MockResult> results = new ArrayList<>();

        for (StudySession session : sessions.findHistory(userId, spaceId,
                PageRequest.of(0, Math.clamp(limit * 5, 5, 100)))) {

            if (session.mode() != SessionMode.EXAM
                    || session.score() == null
                    || session.submittedAt() == null
                    || (session.status() != SessionStatus.SUBMITTED
                        && session.status() != SessionStatus.EXPIRED)) {
                continue;
            }

            results.add(new MockResult(
                    session.id(),
                    session.score().doubleValue(),
                    LocalDate.ofInstant(session.submittedAt(), ZoneOffset.UTC),
                    session.totalItems(),
                    session.answeredCount()));

            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }

    /**
     * @param score 0-100 as recorded on the session
     * @param on    the day it was submitted, for the recency decay
     */
    public record MockResult(UUID sessionId, double score, LocalDate on,
                             int totalItems, int answeredCount) {
    }
}
