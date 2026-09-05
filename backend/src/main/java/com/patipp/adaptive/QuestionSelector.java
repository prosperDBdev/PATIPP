package com.patipp.adaptive;

import com.patipp.adaptive.LearnerModel.TopicKey;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chooses which questions to serve, and says why.
 *
 * <p>Pure: a {@link LearnerModel}, a pool of candidates and a request go in, an ordered list
 * comes out. No database, no clock of its own, no randomness that is not seeded. That is what
 * lets a change to the algorithm be replayed against real history and compared against the
 * version it replaces, instead of being adopted because it felt better.
 */
public interface QuestionSelector {

    String version();

    Selection select(LearnerModel model, List<Candidate> pool, Request request);

    /**
     * One question the selector may choose, with everything it needs to judge it.
     *
     * @param rating             the question's Elo, from {@code question_stats}
     * @param authoredDifficulty the label its author gave it, used while the bank is new
     * @param estimatedSeconds   how long it is expected to take
     */
    record Candidate(
            UUID questionId,
            UUID questionVersionId,
            TopicKey topic,
            double rating,
            String authoredDifficulty,
            int estimatedSeconds) {
    }

    /**
     * @param length          how many questions to return
     * @param seed            makes the draw reproducible; the same seed and model give the
     *                        same session
     * @param targetSuccess   the space's target success rate, or null for the engine default
     * @param temperature     0 selects the top scorers deterministically, which tests want;
     *                        higher values trade a little quality for variety, which people
     *                        want
     * @param singleTopicDrill relaxes the diversity constraint, because the learner has asked
     *                        for one topic and it would be perverse to refuse
     */
    record Request(
            int length,
            long seed,
            Double targetSuccess,
            double temperature,
            boolean singleTopicDrill) {

        /** How much variety to allow by default. */
        public static final double DEFAULT_TEMPERATURE = 0.3;

        public static Request of(int length, long seed) {
            return new Request(length, seed, null, DEFAULT_TEMPERATURE, false);
        }
    }

    /**
     * @param items       the chosen questions, in the order they should be served
     * @param diagnostics engine-level notes about the whole draw, for the "why this session?"
     *                    affordance and for debugging a selection that looks wrong
     */
    record Selection(List<Chosen> items, Map<String, Object> diagnostics) {
    }

    /**
     * One selected question and the reasoning behind it.
     *
     * @param reason       a stable machine key: WEAK_TOPIC, COVERAGE_GAP, DIFFICULTY_FIT,
     *                     RECOVERY, CALIBRATION, DUE_REVIEW
     * @param explanation  the same thing in a sentence, shown to the learner
     * @param expectation  the predicted probability of success, kept so the model's
     *                     calibration can be scored later against what actually happened
     * @param components   each part of the composite score, so a strange choice can be
     *                     accounted for rather than guessed at
     */
    record Chosen(
            UUID questionId,
            UUID questionVersionId,
            double score,
            String reason,
            String explanation,
            double expectation,
            Map<String, Object> components) {
    }
}
