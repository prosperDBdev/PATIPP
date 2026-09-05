package com.patipp.adaptive;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the engine knows about one learner in one preparation space.
 *
 * <p>A read-only snapshot, built once per session and never mutated. Every rule in this
 * module reads only from here, which is what makes the whole engine a pure function: the
 * same model produces the same selection, so a change to the algorithm can be replayed
 * against real history and compared with the version it replaces.
 *
 * <p>Built by the {@code learning} module from JPA rows. Nothing in this package knows that
 * a database exists.
 *
 * @param globalAbility Elo across the whole space, for topics with no history of their own
 * @param topics        keyed by topic id, or by {@link TopicKey#untagged} for questions that
 *                      have no topic - untagged questions are still practised, so they still
 *                      need somewhere to accumulate
 * @param items         per-question history, keyed by question id
 * @param recent        the last few attempts in order, for recovery mode and streak rules
 */
public record LearnerModel(
        UUID userId,
        UUID spaceId,
        double globalAbility,
        Map<TopicKey, TopicState> topics,
        Map<UUID, ItemState> items,
        RecentWindow recent,
        Instant asOf) {

    /** A learner seen for the first time: every estimate is a prior. */
    public static LearnerModel empty(UUID userId, UUID spaceId, Instant asOf) {
        return new LearnerModel(userId, spaceId, Elo.STARTING_RATING,
                Map.of(), Map.of(), RecentWindow.empty(), asOf);
    }

    public LearnerModel {
        topics = topics == null ? Map.of() : Map.copyOf(topics);
        items = items == null ? Map.of() : Map.copyOf(items);
        recent = recent == null ? RecentWindow.empty() : recent;
    }

    /**
     * The learner's ability in a topic, falling back to their ability across the space.
     *
     * <p>The fallback matters on a first encounter with a topic: starting every new topic at
     * the default 1200 would serve a strong learner beginner questions each time they open a
     * new part of the syllabus.
     */
    public double abilityIn(TopicKey topic) {
        TopicState state = topics.get(topic);
        return state == null || state.attempts() == 0 ? globalAbility : state.ability();
    }

    public Optional<TopicState> topic(TopicKey topic) {
        return Optional.ofNullable(topics.get(topic));
    }

    public Optional<ItemState> item(UUID questionId) {
        return Optional.ofNullable(items.get(questionId));
    }

    /** Total answers recorded in this space, which gates how much the engine claims to know. */
    public int totalAttempts() {
        return topics.values().stream().mapToInt(TopicState::attempts).sum();
    }

    /** True while there is too little history for adaptation to mean anything. */
    public boolean isCalibrating() {
        return totalAttempts() < CALIBRATION_ATTEMPTS;
    }

    /**
     * How many answers are needed before the engine stops treating a space as brand new.
     *
     * <p>Roughly one session. Below it, selection spreads across the syllabus at authored
     * difficulty instead of chasing weakness, because "weak" computed from four answers is
     * noise, and a recommendation built on noise is how a learner stops trusting the whole
     * feature.
     */
    public static final int CALIBRATION_ATTEMPTS = 12;

    /**
     * Identifies a bucket of the syllabus: a topic, or a subject's untagged remainder.
     *
     * <p>A record rather than a bare {@code UUID} because {@code topicId} is genuinely
     * nullable and a null map key is a bug waiting to happen. Carrying the subject too means
     * weakness can be rolled up to the subject without a second lookup.
     */
    public record TopicKey(UUID subjectId, UUID topicId) {

        public static TopicKey of(UUID subjectId, UUID topicId) {
            return new TopicKey(subjectId, topicId);
        }

        /** The bucket for questions in a subject that have not been given a topic. */
        public static TopicKey untagged(UUID subjectId) {
            return new TopicKey(subjectId, null);
        }

        public boolean isUntagged() {
            return topicId == null;
        }
    }

    /**
     * What the learner has done in one bucket of the syllabus.
     *
     * @param ability          Elo, learner side
     * @param accuracy         lifetime correct rate, 0-1
     * @param decayedAccuracy  the same weighted by recency, which is the honest measure -
     *                         lifetime accuracy tells you what you knew in March and cannot
     *                         detect that you have gone rusty
     * @param coverage         fraction of this bucket's active questions ever attempted
     * @param consecutiveWrong current run of wrong answers, for the weakness bump
     */
    public record TopicState(
            TopicKey key,
            double weight,
            double ability,
            double accuracy,
            double decayedAccuracy,
            int attempts,
            int correct,
            double coverage,
            int consecutiveWrong,
            Instant lastPracticedAt,
            Instant lastIncorrectAt,
            MasteryLevel level) {

        /** Below this, the engine reports {@link MasteryLevel#UNASSESSED} rather than a verdict. */
        public static final int CONFIDENCE_FLOOR = 5;

        public boolean isAssessed() {
            return attempts >= CONFIDENCE_FLOOR;
        }
    }

    /**
     * What the learner has done with one question.
     *
     * @param dueAt null until Phase 6 gives questions a review schedule; the selector's
     *              retention component reads it and treats null as "new"
     */
    public record ItemState(
            UUID questionId,
            int attempts,
            int correct,
            Instant lastSeenAt,
            boolean lastWasCorrect,
            Instant dueAt,
            double stability) {

        public static ItemState unseen(UUID questionId) {
            return new ItemState(questionId, 0, 0, null, false, null, 0.0);
        }

        public boolean isNew() {
            return attempts == 0;
        }
    }

    /**
     * The last few answers, most recent first.
     *
     * <p>Recovery mode and the streak rules both need "how is this session going right now",
     * which no lifetime aggregate can answer.
     */
    public record RecentWindow(List<Outcome> outcomes) {

        /** How many attempts back the engine looks when judging the current run. */
        public static final int SIZE = 20;

        public RecentWindow {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        public static RecentWindow empty() {
            return new RecentWindow(List.of());
        }

        /** Correct answers among the most recent {@code n}. */
        public int correctInLast(int n) {
            return (int) outcomes.stream().limit(n).filter(Outcome::correct).count();
        }

        public int size() {
            return outcomes.size();
        }

        /** The most recent run of correct answers, however long. */
        public int currentCorrectStreak() {
            int streak = 0;
            for (Outcome outcome : outcomes) {
                if (!outcome.correct()) {
                    break;
                }
                streak++;
            }
            return streak;
        }

        public int currentWrongStreak() {
            int streak = 0;
            for (Outcome outcome : outcomes) {
                if (outcome.correct()) {
                    break;
                }
                streak++;
            }
            return streak;
        }

        /**
         * @param expectation the engine's predicted probability of success at the time, kept
         *                    so calibration can be measured after the fact
         */
        public record Outcome(UUID questionId, TopicKey topic, boolean correct,
                              double expectation, Instant at) {
        }
    }
}
