package com.patipp.learning.api;

import com.patipp.adaptive.LearnerModel;
import com.patipp.adaptive.MasteryLevel;
import com.patipp.adaptive.WeaknessDetector;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.learning.internal.LearnerModelBuilder;
import com.patipp.learning.internal.MasteryRebuilder;
import com.patipp.learning.internal.MasteryUpdater;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final WeaknessDetector weakness;
    private final Clock clock;

    public LearningAccess(LearnerModelBuilder builder, MasteryUpdater updater,
                          MasteryRebuilder rebuilder, TopicMasteryRepository mastery,
                          WeaknessDetector weakness, Clock clock) {
        this.builder = builder;
        this.updater = updater;
        this.rebuilder = rebuilder;
        this.mastery = mastery;
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
