package com.patipp.learning.internal;

import com.patipp.analytics.api.ActivityAccess;
import com.patipp.attempts.domain.QuestionAttempt;
import com.patipp.attempts.domain.QuestionAttemptRepository;
import com.patipp.learning.domain.LearningStateRepository;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.questions.api.QuestionAccess;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Throws away every derived number and recomputes it from the attempt log.
 *
 * <p>This class is the proof of the three-layer design rather than a maintenance tool. If it
 * works, then {@code topic_mastery} and the question ratings really are derived, the attempt
 * log really is the only source of truth, and the adaptive algorithm can be replaced in month
 * four by rebuilding rather than by a migration that guesses at history. If it ever stops
 * working, something has quietly become a source of truth that should not be one.
 *
 * <p>An integration test asserts that a rebuild reproduces exactly what the incremental path
 * produced. That equality is the whole contract.
 */
@Component
public class MasteryRebuilder {

    private static final Logger log = LoggerFactory.getLogger(MasteryRebuilder.class);

    private final TopicMasteryRepository mastery;
    private final QuestionAttemptRepository attempts;
    private final QuestionAccess questions;
    private final MasteryUpdater updater;
    private final LearningStateRepository learningStates;
    private final ReviewScheduleUpdater reviews;
    private final SpaceSettings spaceSettings;
    private final ActivityAccess dailyActivity;

    public MasteryRebuilder(TopicMasteryRepository mastery, QuestionAttemptRepository attempts,
                            QuestionAccess questions, MasteryUpdater updater,
                            LearningStateRepository learningStates,
                            ReviewScheduleUpdater reviews,
                            SpaceSettings spaceSettings,
                            ActivityAccess dailyActivity) {
        this.mastery = mastery;
        this.attempts = attempts;
        this.questions = questions;
        this.updater = updater;
        this.learningStates = learningStates;
        this.reviews = reviews;
        this.spaceSettings = spaceSettings;
        this.dailyActivity = dailyActivity;
    }

    /**
     * Rebuilds one learner's derived state in one space.
     *
     * <p>Replays in the order the answers were actually given. Elo is path-dependent - the
     * same answers in a different order give different ratings - so replay order is part of
     * the contract, not an implementation detail.
     */
    @Transactional
    public Report rebuild(UUID userId, UUID spaceId) {
        List<QuestionAttempt> history = attempts.findAllForLearner(userId, spaceId);

        mastery.deleteForLearner(userId, spaceId);
        // The review schedule is derived too, and rebuilt from the same log. Leaving it alone
        // would quietly make it a source of truth, which is the one thing nothing in layer
        // three may become.
        learningStates.deleteForLearner(userId, spaceId);
        // The day counts are derived from the same log, so they are rebuilt from it too.
        dailyActivity.clear(userId, spaceId);
        // Question ratings go back to the prior their author's label implies, otherwise the
        // replay would start from ratings that already contain the history being replayed.
        questions.resetRatings(spaceId);

        // The blueprint's target retention, read once: it is a property of the space, not of
        // any single attempt, and re-reading it per attempt would be a query per row.
        Map<String, Object> settings = spaceSettings.settingsFor(spaceId);

        // Counted here rather than re-queried per attempt: the incremental path knows how
        // many times a question had been answered before, and the replay has to agree or
        // coverage will come out different.
        Map<UUID, Integer> seenSoFar = new HashMap<>();
        // Cached: a question's estimated time does not change mid-replay, and looking it up per
        // attempt would be a query per row of the log.
        Map<UUID, Integer> estimatedSeconds = new HashMap<>();

        for (QuestionAttempt attempt : history) {
            int prior = seenSoFar.merge(attempt.questionId(), 1, Integer::sum) - 1;

            updater.record(
                    userId,
                    spaceId,
                    attempt.questionId(),
                    attempt.subjectId(),
                    attempt.topicId(),
                    attempt.difficulty(),
                    attempt.score().doubleValue(),
                    attempt.isCorrect(),
                    attempt.responseTimeMs(),
                    prior,
                    attempt.createdAt());

            // Replayed at the instant the answer was actually given, not now. Stability growth
            // depends on how close to forgetting the learner was, so replaying a year of
            // history against today's clock would produce a schedule that never existed.
            reviews.record(
                    userId,
                    spaceId,
                    attempt.questionId(),
                    attempt.difficulty(),
                    attempt.isCorrect(),
                    attempt.responseTimeMs(),
                    attempt.confidence() == null ? null : attempt.confidence().intValue(),
                    attempt.grade(),
                    estimatedSeconds.computeIfAbsent(attempt.questionId(),
                            id -> questions.load(spaceId, id)
                                    .map(QuestionAccess.ServedQuestion::estimatedSeconds)
                                    .orElse(60)),
                    settings,
                    attempt.createdAt());

            dailyActivity.answered(userId, spaceId, attempt.isCorrect(),
                    attempt.responseTimeMs(), attempt.createdAt());
        }

        log.info("Rebuilt derived state for user {} in space {} from {} attempts",
                userId, spaceId, history.size());

        return new Report(
                history.size(),
                mastery.findForLearner(userId, spaceId).size(),
                learningStates.findForLearner(userId, spaceId).size());
    }

    /**
     * @param buckets   how many topic-mastery rows the replay produced
     * @param scheduled how many items came out with a review schedule
     */
    public record Report(int attemptsReplayed, int buckets, int scheduled) {
    }
}
