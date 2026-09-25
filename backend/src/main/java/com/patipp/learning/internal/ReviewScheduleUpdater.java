package com.patipp.learning.internal;

import com.patipp.learning.domain.LearningState;
import com.patipp.learning.domain.LearningStateRepository;
import com.patipp.questions.api.QuestionAccess;
import com.patipp.scheduling.Grade;
import com.patipp.scheduling.GradeDerivation;
import com.patipp.scheduling.ReviewScheduler;
import com.patipp.scheduling.ReviewScheduler.SchedulerConfig;
import com.patipp.scheduling.ReviewState;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds one answer into an item's review schedule.
 *
 * <p>Every format arrives here, not just flashcards. A flashcard reports its own
 * Again/Hard/Good/Easy; everything else has one derived from correctness, response time and
 * confidence. Without that derivation there would be two disconnected systems, where reviewing
 * a fact as a flashcard counted towards retention and answering the same fact as a
 * multiple-choice question did not.
 */
@Component
public class ReviewScheduleUpdater {

    private final LearningStateRepository states;
    private final QuestionAccess questions;
    private final ReviewScheduler scheduler;
    private final SpaceSettings spaceSettings;

    public ReviewScheduleUpdater(LearningStateRepository states, QuestionAccess questions,
                                 ReviewScheduler scheduler, SpaceSettings spaceSettings) {
        this.states = states;
        this.questions = questions;
        this.scheduler = scheduler;
        this.spaceSettings = spaceSettings;
    }

    /**
     * @param reportedGrade the learner's own 1-4 grade when the format produces one, else null
     * @param settings      the space's merged blueprint, for its target retention
     */
    @Transactional
    public Result record(UUID userId, UUID spaceId, UUID questionId, String authoredDifficulty,
                         boolean correct, Integer responseTimeMs, Integer confidence,
                         Short reportedGrade, int estimatedSeconds,
                         Map<String, Object> settings, Instant at) {
        LearningState state = states.findForItem(userId, spaceId, questionId)
                .orElseGet(() -> LearningState.start(userId, spaceId, questionId,
                        authoredDifficulty, scheduler.version()));

        Grade grade = gradeFor(questionId, correct, responseTimeMs, confidence, reportedGrade,
                estimatedSeconds);

        SchedulerConfig config = configFrom(settings);
        ReviewState next = scheduler.next(state.toReviewState(), grade, at, config);

        state.record(next, correct);
        states.save(state);

        return new Result(grade, next.intervalDays(), next.dueAt(), next.phase().name(),
                scheduler.priority(next, at).name());
    }

    /**
     * What each button would schedule for this item, keyed by grade name.
     *
     * <p>Computed from the item's real current state, so the numbers on screen are the numbers
     * that will be stored. A preview that merely approximates would be worse than none: it
     * teaches the learner that what the interface says is decorative.
     */
    @Transactional(readOnly = true)
    public Map<String, Double> preview(UUID userId, UUID spaceId, UUID questionId, Instant at) {
        // Looked up here rather than passed in: a caller that has to supply the authored
        // difficulty and the merged blueprint to see a preview is a caller that will get one of
        // them wrong, and a preview computed from the wrong prior is worse than none.
        String authoredDifficulty = questions.load(spaceId, questionId)
                .map(QuestionAccess.ServedQuestion::difficulty)
                .orElse("MEDIUM");

        ReviewState current = states.findForItem(userId, spaceId, questionId)
                .map(LearningState::toReviewState)
                .orElseGet(() -> ReviewState.newItem(authoredDifficulty));

        Map<String, Double> preview = new java.util.LinkedHashMap<>();
        scheduler.previewIntervals(current, at, configFrom(spaceSettings.settingsFor(spaceId)))
                .forEach((grade, days) -> preview.put(grade.name(), round(days)));
        return preview;
    }

    /** Four decimals: enough for the ten-minute learning step to survive being rounded. */
    private static double round(double days) {
        return Math.round(days * 10_000.0) / 10_000.0;
    }

    /**
     * The grade to schedule from.
     *
     * <p>A reported grade always wins: the learner saying "I barely got that" is better evidence
     * than any timing heuristic. Only when the format cannot produce one is it inferred.
     */
    private Grade gradeFor(UUID questionId, boolean correct, Integer responseTimeMs,
                           Integer confidence, Short reportedGrade, int estimatedSeconds) {
        if (reportedGrade != null) {
            return Grade.of(reportedGrade);
        }

        // The observed mean response time, once there is enough of it to be a mean.
        QuestionAccess.ItemTiming timing = questions.timingOf(questionId)
                .orElse(new QuestionAccess.ItemTiming(null, 0));

        return GradeDerivation.derive(correct, responseTimeMs, confidence, estimatedSeconds,
                timing.averageResponseMs(), timing.sampleCount());
    }

    /** Target retention comes from the space's blueprint, so a space can trade reviews for recall. */
    private static SchedulerConfig configFrom(Map<String, Object> settings) {
        Object defaults = settings == null ? null : settings.get("defaults");
        if (defaults instanceof Map<?, ?> map
                && map.get("targetRetention") instanceof Number retention) {
            return new SchedulerConfig(retention.doubleValue(), null,
                    SchedulerConfig.DEFAULT_MAXIMUM_DAYS);
        }
        return SchedulerConfig.standard();
    }

    /**
     * What the learner is shown after answering.
     *
     * @param grade        the grade actually used, reported or derived - shown so a derived
     *                     HARD on a correct answer is explained rather than mysterious
     * @param intervalDays when it will come back
     */
    public record Result(Grade grade, double intervalDays, Instant dueAt, String phase,
                         String priority) {
    }
}
