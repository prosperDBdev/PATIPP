package com.patipp.sessions.internal;

import com.patipp.sessions.domain.SessionMode;
import com.patipp.sessions.domain.StudySession;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Practice: untimed, immediate feedback, length taken from the request or the space defaults.
 *
 * <p>The most permissive mode, and the one that makes the handler seam worth having - it is
 * almost entirely "no special behaviour", which is exactly what a well-drawn interface should
 * let a simple case say.
 */
@Component
public class PracticeModeHandler implements SessionModeHandler {

    static final int DEFAULT_LENGTH = 10;
    static final int MIN_LENGTH = 1;
    static final int MAX_LENGTH = 100;

    @Override
    public SessionMode mode() {
        return SessionMode.PRACTICE;
    }

    /**
     * No deadline. Practice is for thinking, and a countdown changes how you answer -
     * usefully in an exam, unhelpfully when you are trying to understand something.
     */
    @Override
    public Instant deadlineFor(Map<String, Object> config, Map<String, Object> effectiveSettings,
                               Instant startedAt) {
        return null;
    }

    @Override
    public boolean revealsFeedbackImmediately(StudySession session) {
        return true;
    }

    /**
     * Practice is where adaptation belongs.
     *
     * <p>The point here is to work on what you are actually weak at, at a level that is
     * difficult without being demoralising. That is the opposite of an exam, which must stay
     * a fixed yardstick, and it is why the two modes select differently while sharing
     * everything else.
     */
    @Override
    public SelectionStrategy selectionStrategy() {
        return SelectionStrategy.ADAPTIVE;
    }

    /**
     * Requested length wins, then the preparation type's {@code defaults.sessionLength}, then
     * ten. Reading the blueprint means an interview space proposes eight questions and an
     * exam space twenty without either number being written here.
     */
    @Override
    public int resolveLength(Map<String, Object> config, Map<String, Object> effectiveSettings) {
        Integer requested = readInt(config, "length");
        if (requested != null) {
            return clamp(requested);
        }

        Object defaults = effectiveSettings == null ? null : effectiveSettings.get("defaults");
        if (defaults instanceof Map<?, ?> map) {
            Object value = map.get("sessionLength");
            if (value instanceof Number number) {
                return clamp(number.intValue());
            }
        }
        return DEFAULT_LENGTH;
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

    private static int clamp(int value) {
        return Math.clamp(value, MIN_LENGTH, MAX_LENGTH);
    }
}
