package com.patipp.sessions.internal;

import com.patipp.sessions.domain.SessionMode;
import com.patipp.sessions.domain.StudySession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Exam: timed, feedback withheld until the end, weighted to the blueprint, free navigation.
 *
 * <p>The whole of exam mode is this class. There is no exam entity, no exam attempt log and
 * no separate scoring path - an exam is a {@code study_session} with {@code mode = EXAM}, and
 * everything below is a decision the shared engine asks for at the right moment.
 *
 * <p>The point of a mock is to be a stable yardstick, so this deliberately does <em>not</em>
 * adapt. Adaptive selection arrives in Phase 5 for practice; an exam that quietly got easier
 * when you struggled would make two sittings a fortnight apart incomparable, which is the one
 * thing a mock exists to let you do.
 */
@Component
public class ExamModeHandler implements SessionModeHandler {

    static final int DEFAULT_LENGTH = 30;
    static final int DEFAULT_DURATION_MINUTES = 45;
    static final int MIN_LENGTH = 1;
    static final int MAX_LENGTH = 200;
    static final int MIN_DURATION_MINUTES = 1;
    static final int MAX_DURATION_MINUTES = 360;

    @Override
    public SessionMode mode() {
        return SessionMode.EXAM;
    }

    /**
     * Computed from the start time the server recorded, never from anything the client sends.
     *
     * <p>This is the difference between a mock score that means something and one that does
     * not. The browser renders a countdown for the learner's benefit; the server decides when
     * time is actually up.
     */
    @Override
    public Instant deadlineFor(Map<String, Object> config, Map<String, Object> effectiveSettings,
                               Instant startedAt) {
        return startedAt.plus(Duration.ofMinutes(resolveDurationMinutes(config, effectiveSettings)));
    }

    @Override
    public boolean revealsFeedbackImmediately(StudySession session) {
        return false;
    }

    @Override
    public int resolveLength(Map<String, Object> config, Map<String, Object> effectiveSettings) {
        Integer requested = readInt(config, "length");
        if (requested != null) {
            return Math.clamp(requested, MIN_LENGTH, MAX_LENGTH);
        }
        Integer fromBlueprint = readDefault(effectiveSettings, "examLength");
        return Math.clamp(fromBlueprint == null ? DEFAULT_LENGTH : fromBlueprint,
                MIN_LENGTH, MAX_LENGTH);
    }

    @Override
    public boolean allowsFreeNavigation() {
        return true;
    }

    /**
     * Changing your mind is part of sitting a paper, so it is allowed until you submit.
     *
     * <p>Nothing is overwritten to make this work: the revision is appended to the attempt
     * log like any other answer, and the score counts the latest attempt per question. The
     * history of what you first put down survives, which is exactly what makes "I talked
     * myself out of the right answer" a pattern the analytics can eventually show you.
     */
    @Override
    public boolean allowsAnswerRevision() {
        return true;
    }

    @Override
    public boolean weightsBySubject() {
        return true;
    }

    /**
     * Duration in minutes: the request first, then the preparation type's
     * {@code defaults.examDurationMinutes}, then a plain default.
     *
     * <p>Reading the blueprint is what lets a certification space default to 130 minutes and
     * an academic exam space to 60 without either number appearing in this file.
     */
    static int resolveDurationMinutes(Map<String, Object> config,
                                      Map<String, Object> effectiveSettings) {
        Integer requested = readInt(config, "durationMinutes");
        if (requested != null) {
            return Math.clamp(requested, MIN_DURATION_MINUTES, MAX_DURATION_MINUTES);
        }
        Integer fromBlueprint = readDefault(effectiveSettings, "examDurationMinutes");
        return Math.clamp(fromBlueprint == null ? DEFAULT_DURATION_MINUTES : fromBlueprint,
                MIN_DURATION_MINUTES, MAX_DURATION_MINUTES);
    }

    private static Integer readDefault(Map<String, Object> effectiveSettings, String key) {
        Object defaults = effectiveSettings == null ? null : effectiveSettings.get("defaults");
        if (defaults instanceof Map<?, ?> map && map.get(key) instanceof Number number) {
            return number.intValue();
        }
        return null;
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
