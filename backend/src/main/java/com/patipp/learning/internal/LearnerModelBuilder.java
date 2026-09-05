package com.patipp.learning.internal;

import com.patipp.adaptive.Elo;
import com.patipp.adaptive.LearnerModel;
import com.patipp.adaptive.LearnerModel.ItemState;
import com.patipp.adaptive.LearnerModel.RecentWindow;
import com.patipp.adaptive.LearnerModel.TopicKey;
import com.patipp.adaptive.LearnerModel.TopicState;
import com.patipp.attempts.domain.QuestionAttempt;
import com.patipp.attempts.domain.QuestionAttemptRepository;
import com.patipp.learning.domain.TopicMastery;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.questions.api.QuestionAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns database rows into the pure {@link LearnerModel} the engine reads.
 *
 * <p>This is the whole boundary between the two worlds. Everything above it is Spring and
 * JPA; everything below is plain records that can be built by hand in a unit test. Keeping
 * the translation in one class is what stops persistence concerns leaking into the algorithm
 * a little at a time.
 */
@Component
public class LearnerModelBuilder {

    private final TopicMasteryRepository mastery;
    private final QuestionAttemptRepository attempts;
    private final QuestionAccess questions;
    private final Clock clock;

    public LearnerModelBuilder(TopicMasteryRepository mastery,
                               QuestionAttemptRepository attempts,
                               QuestionAccess questions,
                               Clock clock) {
        this.mastery = mastery;
        this.attempts = attempts;
        this.questions = questions;
        this.clock = clock;
    }

    /**
     * A snapshot for one learner in one space.
     *
     * <p>Read once per session rather than per question. The engine is pure, so a stale
     * snapshot would only ever be stale by one session, and re-reading it for every item
     * would turn a session start into thirty round trips.
     */
    @Transactional(readOnly = true)
    public LearnerModel build(UUID userId, UUID spaceId) {
        Instant now = clock.instant();

        List<TopicMastery> rows = mastery.findForLearner(userId, spaceId);
        if (rows.isEmpty()) {
            return LearnerModel.empty(userId, spaceId, now);
        }

        Map<TopicKey, Integer> bankSizes = new HashMap<>();
        questions.activeCountsByTopic(spaceId).forEach(count ->
                bankSizes.put(TopicKey.of(count.subjectId(), count.topicId()),
                        count.activeQuestions()));

        Map<TopicKey, TopicState> topics = new LinkedHashMap<>();
        double abilitySum = 0;
        int abilityWeight = 0;

        for (TopicMastery row : rows) {
            // Aged to now, not to whenever the row was last written: a learner returning
            // after a month should find their accuracy faded, not frozen where they left it.
            row.decayTo(now);

            TopicKey key = TopicKey.of(row.subjectId(), row.topicId());
            int bankSize = bankSizes.getOrDefault(key, 0);

            topics.put(key, new TopicState(
                    key,
                    1.0,
                    row.ability(),
                    row.accuracy(),
                    row.decayedAccuracy(),
                    row.attempts(),
                    row.correct(),
                    // Computed here rather than stored, so adding questions to a topic
                    // correctly reduces your coverage of it.
                    bankSize == 0 ? 0.0 : Math.min(1.0, (double) row.questionsSeen() / bankSize),
                    row.consecutiveWrong(),
                    row.lastPracticedAt(),
                    row.lastIncorrectAt(),
                    row.masteryLevel()));

            // Weighted by evidence: a topic with sixty answers should count for more in the
            // space-wide estimate than one with three.
            abilitySum += row.ability() * row.attempts();
            abilityWeight += row.attempts();
        }

        double globalAbility = abilityWeight == 0
                ? Elo.STARTING_RATING : abilitySum / abilityWeight;

        return new LearnerModel(userId, spaceId, globalAbility, topics,
                itemStates(attempts.findAllForLearner(userId, spaceId)),
                recentWindow(userId, spaceId), now);
    }

    /**
     * Per-question history, from the attempt log.
     *
     * <p>Read from the log rather than from a derived table because Phase 5 has no
     * {@code learning_states} yet. Phase 6 adds one and this method reads that instead; the
     * record it produces already has the {@code dueAt} and {@code stability} fields waiting.
     */
    private Map<UUID, ItemState> itemStates(List<QuestionAttempt> history) {
        Map<UUID, ItemState> states = new HashMap<>();

        // Oldest first, so each row simply supersedes the last seen values.
        for (QuestionAttempt attempt : history) {
            ItemState existing = states.get(attempt.questionId());
            states.put(attempt.questionId(), new ItemState(
                    attempt.questionId(),
                    (existing == null ? 0 : existing.attempts()) + 1,
                    (existing == null ? 0 : existing.correct()) + (attempt.isCorrect() ? 1 : 0),
                    attempt.createdAt(),
                    attempt.isCorrect(),
                    // Phase 6 fills these from learning_states; until then every item reads
                    // as new and the selector's retention component stays inert.
                    null, 0.0));
        }

        return states;
    }

    /** The last few answers, newest first, for recovery mode and the streak rules. */
    private RecentWindow recentWindow(UUID userId, UUID spaceId) {
        List<RecentWindow.Outcome> outcomes = new ArrayList<>(RecentWindow.SIZE);

        for (QuestionAttempt attempt : attempts.findRecent(
                userId, spaceId, PageRequest.of(0, RecentWindow.SIZE))) {
            outcomes.add(new RecentWindow.Outcome(
                    attempt.questionId(),
                    TopicKey.of(attempt.subjectId(), attempt.topicId()),
                    attempt.isCorrect(),
                    // The prediction made at the time is not stored on the attempt yet; it is
                    // only needed by the replay harness, which recomputes it from the ratings.
                    0.0,
                    attempt.createdAt()));
        }

        return new RecentWindow(outcomes);
    }
}
