package com.patipp.learning.internal;

import com.patipp.attempts.domain.QuestionAttempt;
import com.patipp.attempts.domain.QuestionAttemptRepository;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.questions.api.QuestionAccess;
import java.util.HashMap;
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

    public MasteryRebuilder(TopicMasteryRepository mastery, QuestionAttemptRepository attempts,
                            QuestionAccess questions, MasteryUpdater updater) {
        this.mastery = mastery;
        this.attempts = attempts;
        this.questions = questions;
        this.updater = updater;
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
        // Question ratings go back to the prior their author's label implies, otherwise the
        // replay would start from ratings that already contain the history being replayed.
        questions.resetRatings(spaceId);

        // Counted here rather than re-queried per attempt: the incremental path knows how
        // many times a question had been answered before, and the replay has to agree or
        // coverage will come out different.
        Map<UUID, Integer> seenSoFar = new HashMap<>();

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
        }

        log.info("Rebuilt derived state for user {} in space {} from {} attempts",
                userId, spaceId, history.size());

        return new Report(history.size(), mastery.findForLearner(userId, spaceId).size());
    }

    /** @param buckets how many topic-mastery rows the replay produced */
    public record Report(int attemptsReplayed, int buckets) {
    }
}
