package com.patipp.learning.api;

import com.patipp.adaptive.LearnerModel;
import com.patipp.adaptive.MasteryLevel;
import com.patipp.adaptive.WeaknessDetector;
import com.patipp.learning.domain.LearningStateRepository;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.learning.internal.LearnerModelBuilder;
import com.patipp.learning.internal.MasteryRebuilder;
import com.patipp.learning.internal.MasteryUpdater;
import com.patipp.learning.internal.ReviewScheduleUpdater;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the learning module publishes.
 *
 * <p>{@code sessions} needs three things from here: a learner model to select with, somewhere
 * to report an answer, and the weak-topic ranking to show. It gets those and nothing else -
 * not the repositories, not the entity, not the rebuild internals. An ArchUnit rule keeps
 * other modules out of {@code learning.internal}; this is the sanctioned way through.
 */
@Component
public class LearningAccess {

    private final LearnerModelBuilder builder;
    private final MasteryUpdater updater;
    private final MasteryRebuilder rebuilder;
    private final TopicMasteryRepository mastery;
    private final LearningStateRepository learningStates;
    private final ReviewScheduleUpdater reviews;
    private final WeaknessDetector weakness;
    private final Clock clock;

    public LearningAccess(LearnerModelBuilder builder, MasteryUpdater updater,
                          MasteryRebuilder rebuilder, TopicMasteryRepository mastery,
                          LearningStateRepository learningStates,
                          ReviewScheduleUpdater reviews,
                          WeaknessDetector weakness, Clock clock) {
        this.builder = builder;
        this.updater = updater;
        this.rebuilder = rebuilder;
        this.mastery = mastery;
        this.learningStates = learningStates;
        this.reviews = reviews;
        this.weakness = weakness;
        this.clock = clock;
    }

    /** A snapshot of what the engine knows, built once per session. */
    @Transactional(readOnly = true)
    public LearnerModel modelFor(UUID userId, UUID spaceId) {
        return builder.build(userId, spaceId);
    }

    /** Folds one answer into the derived state, moving both Elo ratings. */
    @Transactional
    public MasteryUpdater.Result recordAnswer(UUID userId, UUID spaceId, UUID questionId,
                                              UUID subjectId, UUID topicId,
                                              String authoredDifficulty, double score,
                                              boolean correct, Integer responseTimeMs,
                                              int priorAttemptsOnQuestion, Instant at) {
        return updater.record(userId, spaceId, questionId, subjectId, topicId,
                authoredDifficulty, score, correct, responseTimeMs, priorAttemptsOnQuestion, at);
    }

    /**
     * Folds the same answer into the item's review schedule.
     *
     * <p>Every format, not only flashcards. A grade the learner reported is used as given; for
     * everything else one is derived from correctness, response time and confidence, so that
     * answering a fact as a multiple-choice question counts towards retention exactly as
     * reviewing it as a flashcard does.
     */
    @Transactional
    public Scheduled recordReview(UUID userId, UUID spaceId, UUID questionId,
                                  String authoredDifficulty, boolean correct,
                                  Integer responseTimeMs, Integer confidence,
                                  Short reportedGrade, int estimatedSeconds,
                                  Map<String, Object> settings, Instant at) {
        ReviewScheduleUpdater.Result result = reviews.record(userId, spaceId, questionId,
                authoredDifficulty, correct, responseTimeMs, confidence, reportedGrade,
                estimatedSeconds, settings, at);

        // Mapped to a published record rather than returning the internal one: other modules
        // must not depend on learning.internal, and an ArchUnit rule fails the build if they do.
        return new Scheduled(result.grade().name(), result.intervalDays(), result.dueAt(),
                result.phase(), result.priority());
    }

    /**
     * What the scheduler decided about one item.
     *
     * @param grade        the grade actually used, whether reported by the learner or derived
     * @param intervalDays how long until it comes back
     */
    public record Scheduled(String grade, double intervalDays, Instant dueAt, String phase,
                            String priority) {
    }

    /**
     * What each answer would schedule for this item, before one is chosen.
     *
     * <p>Shown on the buttons themselves, which turns a self-report from a guess into a decision
     * with visible consequences.
     */
    @Transactional(readOnly = true)
    public Map<String, Double> intervalPreview(UUID userId, UUID spaceId, UUID questionId) {
        return reviews.preview(userId, spaceId, questionId, clock.instant());
    }

    /**
     * When each tracked item is next due, for building a review queue.
     *
     * <p>Suspended items are left out entirely rather than reported with their stored date: a
     * parked item is not due and must never be treated as overdue.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Instant> dueDatesFor(UUID userId, UUID spaceId) {
        Map<UUID, Instant> due = new java.util.HashMap<>();
        for (var state : learningStates.findForLearner(userId, spaceId)) {
            if (state.phase() != com.patipp.scheduling.ReviewState.Phase.SUSPENDED
                    && state.dueAt() != null) {
                due.put(state.questionId(), state.dueAt());
            }
        }
        return due;
    }

    /**
     * The clock the scheduler is using.
     *
     * <p>Published so a caller comparing due dates uses the same instant the schedule was
     * written against, rather than {@code Instant.now()} — which is what makes a timed test of
     * review behaviour possible at all.
     */
    public Instant now() {
        return clock.instant();
    }

    /** How much review debt is outstanding in this space. */
    @Transactional(readOnly = true)
    public ReviewDebt reviewDebt(UUID userId, UUID spaceId) {
        Instant now = clock.instant();
        return new ReviewDebt(
                learningStates.countDue(userId, spaceId, now),
                learningStates.countStruggling(userId, spaceId, now),
                learningStates.findForLearner(userId, spaceId).size());
    }

    /**
     * @param due         how many items are ready to be reviewed now
     * @param struggling  of those, how many have been forgotten more than once
     * @param tracked     how many items have a schedule at all
     */
    public record ReviewDebt(int due, int struggling, int tracked) {
    }

    /**
     * What to work on, worst first, and what has not been measured yet.
     *
     * <p>Two lists rather than one ranking, because "you are weak here" and "you have not
     * tested yourself here" are different instructions and conflating them produces advice
     * built on two data points.
     */
    @Transactional(readOnly = true)
    public Focus focusFor(UUID userId, UUID spaceId) {
        LearnerModel model = builder.build(userId, spaceId);
        Instant now = clock.instant();
        return new Focus(
                weakness.rank(model, now),
                weakness.needsAssessment(model),
                model.isCalibrating(),
                model.totalAttempts(),
                model.globalAbility());
    }

    /** Every bucket's current state, for the analytics screens. */
    @Transactional(readOnly = true)
    public List<TopicMasterySummary> masteryFor(UUID userId, UUID spaceId) {
        Instant now = clock.instant();
        return mastery.findForLearner(userId, spaceId).stream()
                .map(row -> {
                    row.decayTo(now);
                    return new TopicMasterySummary(
                            row.subjectId(), row.topicId(), row.ability(), row.accuracy(),
                            row.decayedAccuracy(), row.attempts(), row.correct(),
                            row.questionsSeen(), row.masteryLevel(), row.lastPracticedAt());
                })
                .toList();
    }

    /** Discards the derived state and recomputes it from the attempt log. */
    @Transactional
    public MasteryRebuilder.Report rebuild(UUID userId, UUID spaceId) {
        return rebuilder.rebuild(userId, spaceId);
    }

    /**
     * @param calibrating true while there is too little history for the ranking to mean much,
     *                    which the UI must say rather than hide
     */
    public record Focus(
            List<WeaknessDetector.Weakness> weakest,
            List<WeaknessDetector.Weakness> needsAssessment,
            boolean calibrating,
            int totalAttempts,
            double ability) {
    }

    public record TopicMasterySummary(
            UUID subjectId,
            UUID topicId,
            double ability,
            double accuracy,
            double decayedAccuracy,
            int attempts,
            int correct,
            int questionsSeen,
            MasteryLevel level,
            Instant lastPracticedAt) {
    }
}
