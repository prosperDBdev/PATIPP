package com.patipp.learning.internal;

import com.patipp.adaptive.AbilityEstimator;
import com.patipp.adaptive.Elo;
import com.patipp.learning.domain.TopicMastery;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.questions.api.QuestionAccess;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds one answer into the learner's derived state.
 *
 * <p>Incremental because it runs inside answering a question and must be cheap; correct
 * because {@link MasteryRebuilder} can reproduce the same numbers from the log, and a test
 * asserts that the two agree. That pairing - a fast path and a slow path that must match - is
 * what makes it safe to keep derived state at all.
 */
@Component
public class MasteryUpdater {

    private final TopicMasteryRepository mastery;
    private final QuestionAccess questions;
    private final AbilityEstimator estimator;

    public MasteryUpdater(TopicMasteryRepository mastery, QuestionAccess questions,
                          AbilityEstimator estimator) {
        this.mastery = mastery;
        this.questions = questions;
        this.estimator = estimator;
    }

    /**
     * Moves the learner's ability in this bucket and the question's difficulty, together.
     *
     * @param priorAttemptsOnQuestion how many times this learner had already answered this
     *                                question here, so coverage counts distinct questions
     *                                rather than repetitions
     */
    @Transactional
    public Result record(UUID userId, UUID spaceId, UUID questionId, UUID subjectId,
                         UUID topicId, String authoredDifficulty, double score,
                         boolean correct, Integer responseTimeMs, int priorAttemptsOnQuestion,
                         Instant at) {
        TopicMastery bucket = bucketFor(userId, spaceId, subjectId, topicId);
        // Ageing before reading is what makes the ability the estimator sees current rather
        // than whatever it was when this bucket was last touched.
        bucket.decayTo(at);

        QuestionAccess.ItemRating rating = questions.ratingOf(questionId)
                .orElseGet(() -> new QuestionAccess.ItemRating(
                        Elo.seedFor(authoredDifficulty), 0));

        AbilityEstimator.Update update = estimator.update(
                bucket.ability(), rating.rating(), score, bucket.attempts(),
                rating.ratingCount());

        bucket.record(score, correct, responseTimeMs, priorAttemptsOnQuestion == 0,
                update.learnerRating(), at);
        mastery.save(bucket);

        // The item side lives with the question, because it is a fact about the question
        // rather than about this learner.
        questions.applyRating(questionId, update.itemRating());

        return new Result(update.learnerRating(), update.itemRating(), update.expectation(),
                bucket.masteryLevel().name());
    }

    /**
     * Finds or creates the bucket, seeding a new one from the learner's ability elsewhere.
     *
     * <p>Seeding from the space-wide estimate rather than from the default 1200 matters: a
     * strong learner opening a new topic should not be handed beginner questions simply
     * because that topic has no history yet.
     */
    private TopicMastery bucketFor(UUID userId, UUID spaceId, UUID subjectId, UUID topicId) {
        return (topicId == null
                ? mastery.findUntagged(userId, spaceId, subjectId)
                : mastery.findForTopic(userId, spaceId, topicId))
                .orElseGet(() -> TopicMastery.start(userId, spaceId, subjectId, topicId,
                        seedAbility(userId, spaceId), estimator.version()));
    }

    private double seedAbility(UUID userId, UUID spaceId) {
        double sum = 0;
        int weight = 0;
        for (TopicMastery row : mastery.findForLearner(userId, spaceId)) {
            sum += row.ability() * row.attempts();
            weight += row.attempts();
        }
        return weight == 0 ? Elo.STARTING_RATING : sum / weight;
    }

    /** @param expectation what the engine predicted, before the answer was known */
    public record Result(double learnerAbility, double itemRating, double expectation,
                         String masteryLevel) {
    }
}
