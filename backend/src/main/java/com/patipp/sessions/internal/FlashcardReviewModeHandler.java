package com.patipp.sessions.internal;

import com.patipp.sessions.domain.SessionMode;
import com.patipp.sessions.domain.StudySession;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Flashcard review: whatever is due, in the order the scheduler says.
 *
 * <p>Notice how little is here. A review session is not a separate subsystem — it is the same
 * engine, the same attempt log and the same analytics, selecting by <em>due date</em> instead of
 * by weakness. That is what the roadmap meant by "a flashcard is a question type, not a separate
 * system", and it is why Again/Hard/Good/Easy feeds exam readiness for free.
 *
 * <p>It also does not restrict itself to flashcards. Anything with review debt belongs in a
 * review session, including the multiple-choice question you got wrong last week, because the
 * scheduler tracks every format.
 */
@Component
public class FlashcardReviewModeHandler implements SessionModeHandler {

    static final int DEFAULT_LENGTH = 20;
    static final int MIN_LENGTH = 1;
    static final int MAX_LENGTH = 200;

    @Override
    public SessionMode mode() {
        return SessionMode.FLASHCARD_REVIEW;
    }

    /**
     * No deadline. Rushing a review is how you turn recall practice into recognition practice.
     */
    @Override
    public Instant deadlineFor(Map<String, Object> config, Map<String, Object> effectiveSettings,
                               Instant startedAt) {
        return null;
    }

    /**
     * Immediate, and unavoidable.
     *
     * <p>Reviewing without being told the answer is not reviewing. The whole mechanism is:
     * try to recall, see whether you were right, report how it went.
     */
    @Override
    public boolean revealsFeedbackImmediately(StudySession session) {
        return true;
    }

    @Override
    public int resolveLength(Map<String, Object> config, Map<String, Object> effectiveSettings) {
        Integer requested = readInt(config, "length");
        if (requested != null) {
            return Math.clamp(requested, MIN_LENGTH, MAX_LENGTH);
        }

        Object defaults = effectiveSettings == null ? null : effectiveSettings.get("defaults");
        if (defaults instanceof Map<?, ?> map && map.get("reviewLength") instanceof Number number) {
            return Math.clamp(number.intValue(), MIN_LENGTH, MAX_LENGTH);
        }
        return DEFAULT_LENGTH;
    }

    /**
     * Selection is by review debt, not by weakness.
     *
     * <p>The difference matters: practice should chase what you are bad at, while a review
     * session should clear what is about to be forgotten. An item you know perfectly well is
     * still worth reviewing on the day its interval expires, and a topic you are weak at is not
     * worth reviewing twice in an hour.
     */
    @Override
    public SelectionStrategy selectionStrategy() {
        return SelectionStrategy.DUE_FIRST;
    }

    /**
     * Answers can be changed, because {@code Again} has to be able to bring a card back.
     *
     * <p>Within one sitting a lapsed card is due again in ten minutes, so the same item can
     * legitimately be answered twice in one session.
     */
    @Override
    public boolean allowsAnswerRevision() {
        return true;
    }

    private static Integer readInt(Map<String, Object> source, String key) {
        Object raw = source == null ? null : source.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? null : Integer.valueOf(raw.toString().strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
