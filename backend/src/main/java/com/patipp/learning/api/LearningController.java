package com.patipp.learning.api;

import com.patipp.adaptive.WeaknessDetector.Weakness;
import com.patipp.common.security.CurrentUser;
import com.patipp.curriculum.api.CurriculumLookup;
import com.patipp.learning.internal.MasteryRebuilder;
import com.patipp.preparations.api.SpaceAccessGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the engine currently believes, and a way to make it recompute from scratch.
 *
 * <p>Names rather than ids in the responses. A weak-topic list keyed by UUID is not something
 * a person can act on, and resolving those ids is this module's job rather than the browser's.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class LearningController {

    private final LearningAccess learning;
    private final SpaceAccessGuard accessGuard;
    private final CurriculumLookup curriculum;
    private final CurrentUser currentUser;

    public LearningController(LearningAccess learning, SpaceAccessGuard accessGuard,
                              CurriculumLookup curriculum, CurrentUser currentUser) {
        this.learning = learning;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
        this.curriculum = curriculum;
    }

    /**
     * What to work on next, and what has not been measured yet.
     *
     * <p>Two lists, deliberately. "You are weak here" and "you have not tested yourself here"
     * call for different actions, and merging them would rank a topic with two attempts
     * alongside one with sixty.
     */
    @GetMapping("/focus")
    public FocusResponse focus(@PathVariable UUID spaceId) {
        accessGuard.requireOwned(spaceId);
        UUID userId = currentUser.requireId();

        LearningAccess.Focus focus = learning.focusFor(userId, spaceId);
        Map<UUID, String> subjects = curriculum.subjectNames(spaceId);
        Map<UUID, String> topics = curriculum.topicNames(spaceId);

        return new FocusResponse(
                focus.weakest().stream().map(w -> named(w, subjects, topics)).toList(),
                focus.needsAssessment().stream().map(w -> named(w, subjects, topics)).toList(),
                focus.calibrating(),
                focus.totalAttempts(),
                Math.round(focus.ability()),
                // Said plainly rather than hidden behind a spinner: an engine that admits it
                // does not know yet is one you can still trust when it does.
                focus.calibrating()
                        ? "Still getting to know you — answer a few more to see where you stand"
                        : null);
    }

    /** Every bucket's current state, for the analytics screens. */
    @GetMapping("/mastery")
    public List<MasteryResponse> mastery(@PathVariable UUID spaceId) {
        accessGuard.requireOwned(spaceId);
        Map<UUID, String> subjects = curriculum.subjectNames(spaceId);
        Map<UUID, String> topics = curriculum.topicNames(spaceId);

        return learning.masteryFor(currentUser.requireId(), spaceId).stream()
                .map(row -> new MasteryResponse(
                        row.subjectId(),
                        subjects.getOrDefault(row.subjectId(), "Unassigned"),
                        row.topicId(),
                        row.topicId() == null ? null
                                : topics.getOrDefault(row.topicId(), "Untagged"),
                        Math.round(row.ability()),
                        percent(row.accuracy()),
                        percent(row.decayedAccuracy()),
                        row.attempts(),
                        row.correct(),
                        row.questionsSeen(),
                        row.level().name(),
                        row.lastPracticedAt() == null ? null : row.lastPracticedAt().toString()))
                .toList();
    }

    /**
     * Throws away the derived state and recomputes it from the attempt log.
     *
     * <p>Safe by construction, and exposed rather than hidden because it is the operation that
     * demonstrates the design: nothing here is a source of truth, so nothing here can be lost.
     * It is also what you run after changing the algorithm.
     */
    @PostMapping("/derived-state/rebuild")
    public MasteryRebuilder.Report rebuild(@PathVariable UUID spaceId) {
        accessGuard.requireWritable(spaceId);
        return learning.rebuild(currentUser.requireId(), spaceId);
    }

    private static WeaknessResponse named(Weakness weakness, Map<UUID, String> subjects,
                                          Map<UUID, String> topics) {
        UUID topicId = weakness.topic().topicId();
        return new WeaknessResponse(
                weakness.topic().subjectId(),
                subjects.getOrDefault(weakness.topic().subjectId(), "Unassigned"),
                topicId,
                topicId == null ? null : topics.getOrDefault(topicId, "Untagged"),
                Math.round(weakness.score() * 100) / 100.0,
                percent(weakness.decayedAccuracy()),
                weakness.attempts(),
                weakness.level().name());
    }

    private static int percent(double fraction) {
        return (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * 100);
    }

    /**
     * @param ability     the learner's Elo across the space, shown as a number they can watch
     *                    move rather than as a grade
     * @param note        present only when the engine wants to say it is not confident yet
     */
    public record FocusResponse(
            List<WeaknessResponse> weakest,
            List<WeaknessResponse> needsAssessment,
            boolean calibrating,
            int totalAttempts,
            long ability,
            String note) {
    }

    /** @param topicName null for a subject's untagged questions */
    public record WeaknessResponse(
            UUID subjectId,
            String subjectName,
            UUID topicId,
            String topicName,
            double score,
            int recentAccuracy,
            int attempts,
            String level) {
    }

    public record MasteryResponse(
            UUID subjectId,
            String subjectName,
            UUID topicId,
            String topicName,
            long ability,
            int accuracy,
            int recentAccuracy,
            int attempts,
            int correct,
            int questionsSeen,
            String level,
            String lastPracticedAt) {
    }
}
