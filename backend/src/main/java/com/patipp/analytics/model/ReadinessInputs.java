package com.patipp.analytics.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the readiness model reads, as plain values.
 *
 * <p>Assembled by the {@code analytics} module from the attempt log, the mastery table and the
 * review schedule. Nothing here knows a database exists, which is what lets a hundred synthetic
 * learners be built by hand in a test and scored in milliseconds — and that matters because the
 * only honest way to judge a change to the weights is to see what it does to many learners at
 * once.
 *
 * @param subjects          per-subject evidence, weighted as the curriculum says
 * @param totalAttempts     answers recorded in this space, which gates how much the score claims
 * @param targetDate        the exam date, or null; used for nothing except reporting urgency
 * @param reviewsDue        review items ready now
 * @param reviewsOverdue    of those, badly overdue - past the interval that was scheduled
 * @param reviewsTracked    items with a schedule at all
 * @param studyDaysLast14   distinct days with at least one answer in the last fortnight
 * @param targetStudyDays   how many of those fourteen the learner is aiming for
 * @param recentMockScores  finished exam scores, newest first, 0-100
 * @param mockAges          how many days ago each of those was sat, in the same order
 * @param depthExpectation  mean chance of success against items at the space's target
 *                          difficulty, 0-1; null when there is nothing to measure it against
 */
public record ReadinessInputs(
        List<SubjectEvidence> subjects,
        int totalAttempts,
        Instant asOf,
        java.time.LocalDate targetDate,
        int reviewsDue,
        int reviewsOverdue,
        int reviewsTracked,
        int studyDaysLast14,
        int targetStudyDays,
        List<Double> recentMockScores,
        List<Integer> mockAges,
        Double depthExpectation) {

    public ReadinessInputs {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        recentMockScores = recentMockScores == null ? List.of() : List.copyOf(recentMockScores);
        mockAges = mockAges == null ? List.of() : List.copyOf(mockAges);
        targetStudyDays = targetStudyDays <= 0 ? DEFAULT_TARGET_STUDY_DAYS : targetStudyDays;
    }

    /**
     * Ten days in fourteen.
     *
     * <p>Not fourteen. A target that demands perfection reports failure for taking a weekend
     * off, and a metric that punishes rest is one people learn to ignore.
     */
    public static final int DEFAULT_TARGET_STUDY_DAYS = 10;

    /**
     * What one subject contributes.
     *
     * @param weight           the curriculum weighting, so being weak at something worth thirty
     *                         percent of the paper counts for more than at something worth five
     * @param topicsTotal      how many buckets the subject has
     * @param topicsAssessed   how many have passed the five-attempt confidence floor
     * @param decayedAccuracy  recency-weighted correct rate across the subject, 0-1
     * @param difficultyWeight mean authored difficulty of what was answered, 1 EASY to 4 EXPERT,
     *                         so being right about hard questions counts for more than being
     *                         right about easy ones
     * @param questionsSeen    distinct questions attempted
     * @param questionsTotal   distinct questions available
     */
    public record SubjectEvidence(
            String name,
            double weight,
            int topicsTotal,
            int topicsAssessed,
            double decayedAccuracy,
            double difficultyWeight,
            int attempts,
            int questionsSeen,
            int questionsTotal) {

        double effectiveWeight() {
            return weight <= 0 ? 1.0 : Math.min(3.0, weight);
        }
    }

    /** Blueprint weights for the six components, normalised so they always sum to one. */
    public record ComponentWeights(Map<String, Double> byComponent) {

        /** Used when a blueprint says nothing. Accuracy leads; consistency is a tiebreaker. */
        public static ComponentWeights standard() {
            return new ComponentWeights(Map.of(
                    "coverage", 0.20,
                    "accuracy", 0.25,
                    "depth", 0.15,
                    "retention", 0.15,
                    "consistency", 0.10,
                    "mock", 0.15));
        }

        public ComponentWeights {
            Map<String, Double> provided = byComponent == null || byComponent.isEmpty()
                    ? standardValues()
                    : byComponent;

            double total = provided.values().stream()
                    .mapToDouble(value -> Math.max(0.0, value))
                    .sum();

            if (total <= 0) {
                byComponent = standardValues();
            } else {
                // Normalised rather than trusted. A blueprint whose weights sum to 1.4 would
                // otherwise produce a readiness of 140%, and the author of that blueprint would
                // have no idea why.
                java.util.Map<String, Double> scaled = new java.util.LinkedHashMap<>();
                provided.forEach((key, value) -> scaled.put(key, Math.max(0.0, value) / total));
                byComponent = Map.copyOf(scaled);
            }
        }

        private static Map<String, Double> standardValues() {
            return Map.of(
                    "coverage", 0.20, "accuracy", 0.25, "depth", 0.15,
                    "retention", 0.15, "consistency", 0.10, "mock", 0.15);
        }

        public double weightFor(String component) {
            return byComponent.getOrDefault(component, 0.0);
        }
    }
}
