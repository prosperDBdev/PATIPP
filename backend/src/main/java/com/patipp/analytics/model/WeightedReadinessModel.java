package com.patipp.analytics.model;

import com.patipp.analytics.model.ReadinessInputs.ComponentWeights;
import com.patipp.analytics.model.ReadinessInputs.SubjectEvidence;
import com.patipp.analytics.model.ReadinessResult.ConfidenceBand;
import com.patipp.analytics.model.ReadinessResult.Lever;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Six weighted components, scaled by how much evidence exists.
 *
 * <p>Deliberately not the average quiz percentage. That number rises when you answer easy
 * questions about things you already know, falls when you tackle something hard, and says nothing
 * at all about whether you have seen the syllabus or will still remember it next week. Six
 * separate judgements, each answering a different question a learner actually has:
 *
 * <ul>
 *   <li><b>Coverage</b> — have I seen the syllabus?</li>
 *   <li><b>Accuracy</b> — am I getting things right?</li>
 *   <li><b>Depth</b> — at the level the real thing demands?</li>
 *   <li><b>Retention</b> — will I still know it on the day?</li>
 *   <li><b>Consistency</b> — am I actually studying?</li>
 *   <li><b>Mock</b> — does it hold up under exam conditions?</li>
 * </ul>
 *
 * <p>Separating them is what produces the one genuinely useful output: not the score, but which
 * of the six is weakest and therefore what to do next.
 */
public final class WeightedReadinessModel implements ReadinessModel {

    public static final String VERSION = "WEIGHTED_V1";

    /** Attempts per subject below which a topic is not counted as covered. */
    static final int CONFIDENCE_FLOOR = 5;

    /** Evidence at which the confidence factor reaches 1.0. */
    static final int FULL_CONFIDENCE_ATTEMPTS = 150;

    /** The floor of the confidence factor, so an early score is capped rather than zeroed. */
    static final double CONFIDENCE_FLOOR_FACTOR = 0.55;

    /** A delta smaller than this is noise and is not reported as a driver. */
    private static final double REPORTABLE_DELTA = 0.5;

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ReadinessResult compute(ReadinessInputs inputs, ComponentWeights weights,
                                   Map<String, Double> previous) {
        ComponentWeights effective = weights == null ? ComponentWeights.standard() : weights;

        Map<String, Double> components = new LinkedHashMap<>();
        components.put("coverage", coverage(inputs));
        components.put("accuracy", accuracy(inputs));
        components.put("depth", depth(inputs));
        components.put("retention", retention(inputs));
        components.put("consistency", consistency(inputs));
        components.put("mock", mock(inputs));

        Map<String, Double> appliedWeights = new LinkedHashMap<>();
        double raw = 0;
        for (Map.Entry<String, Double> entry : components.entrySet()) {
            double weight = effective.weightFor(entry.getKey());
            // Four decimals, not one. A weight is a fraction of one, and six weights rounded to
            // 0.2 sum to 1.2 - which would report a readiness of 112% and make the stored
            // breakdown unusable for the one thing it exists for, recomputing the score by hand.
            appliedWeights.put(entry.getKey(), roundWeight(weight));
            raw += weight * entry.getValue();
        }

        double confidence = confidenceFactor(inputs.totalAttempts());
        double score = raw * confidence;
        ConfidenceBand band = ConfidenceBand.of(inputs.totalAttempts());

        Map<String, Double> deltas = deltas(components, score, previous);

        return new ReadinessResult(
                VERSION,
                round(score),
                round(raw),
                // Three decimals. Rounded to one, a confidence of 0.613 is reported as 0.6 - a
                // two percent error against the multiplier actually used, so the response's own
                // numbers would not reconcile and the breakdown would stop being checkable.
                roundConfidence(confidence),
                band,
                round(components),
                appliedWeights,
                deltas,
                drivers(deltas, inputs),
                biggestLever(components, appliedWeights, inputs),
                headline(score, band, inputs));
    }

    /* ------------------------------------------------------------------ components */

    /**
     * Have you seen the syllabus?
     *
     * <p>Counts topics that have passed the confidence floor, not topics touched once. Two
     * answers in a topic is not coverage of it, and a score that says otherwise would let
     * someone reach the exam having genuinely seen a fifth of the material.
     */
    static double coverage(ReadinessInputs inputs) {
        if (inputs.subjects().isEmpty()) {
            return 0;
        }

        double weighted = 0;
        double totalWeight = 0;

        for (SubjectEvidence subject : inputs.subjects()) {
            double weight = subject.effectiveWeight();
            totalWeight += weight;

            // Two ways of being covered, and the lower of them wins: enough attempts spread
            // across the topics, and enough of the actual question bank seen. A subject with one
            // topic and forty unseen questions is not covered because its single topic is.
            double byTopic = subject.topicsTotal() == 0
                    ? (subject.attempts() >= CONFIDENCE_FLOOR ? 1.0 : 0.0)
                    : (double) subject.topicsAssessed() / subject.topicsTotal();
            double byQuestion = subject.questionsTotal() == 0
                    ? 0.0
                    : Math.min(1.0, (double) subject.questionsSeen() / subject.questionsTotal());

            weighted += weight * Math.min(byTopic, Math.max(byQuestion, byTopic * 0.5));
        }

        return totalWeight == 0 ? 0 : 100.0 * weighted / totalWeight;
    }

    /**
     * Are you getting them right, weighted by difficulty and recency?
     *
     * <p>Recency-weighted because lifetime accuracy describes what you knew in March.
     * Difficulty-weighted because being right about EXPERT questions is worth more than being
     * right about EASY ones, and a score that treats them alike can be inflated by drilling the
     * easy end of the bank.
     */
    static double accuracy(ReadinessInputs inputs) {
        double weighted = 0;
        double totalWeight = 0;

        for (SubjectEvidence subject : inputs.subjects()) {
            if (subject.attempts() == 0) {
                continue;
            }
            // Subject weight times difficulty weight: a hard question in a heavily weighted
            // subject is the most informative thing in the bank.
            double weight = subject.effectiveWeight()
                    * Math.max(0.5, Math.min(2.0, subject.difficultyWeight() / 2.0));
            weighted += weight * Math.clamp(subject.decayedAccuracy(), 0.0, 1.0);
            totalWeight += weight;
        }

        return totalWeight == 0 ? 0 : 100.0 * weighted / totalWeight;
    }

    /**
     * Are you operating at the level the real thing demands?
     *
     * <p>The mean chance of success against items at the space's target difficulty. Distinct from
     * accuracy: someone who only ever answers easy questions can be at 95% accuracy and have no
     * depth at all, which is exactly the learner a single percentage would flatter.
     */
    static double depth(ReadinessInputs inputs) {
        if (inputs.depthExpectation() == null) {
            return 0;
        }
        // Scaled so meeting the target expectation reads as a good score rather than as the
        // target itself: succeeding 78% of the time against target-difficulty items is strong.
        double expectation = Math.clamp(inputs.depthExpectation(), 0.0, 1.0);
        return 100.0 * Math.min(1.0, expectation / 0.78);
    }

    /**
     * Will you still know it on the day?
     *
     * <p>One minus the share of the schedule that has slipped. Nothing tracked yet is zero rather
     * than a hundred: an untouched schedule is not perfect retention, it is no evidence at all,
     * and reporting it as perfect would be the single most flattering lie available.
     */
    static double retention(ReadinessInputs inputs) {
        if (inputs.reviewsTracked() == 0) {
            return 0;
        }
        double slipped = (double) inputs.reviewsDue() / inputs.reviewsTracked();
        // Badly overdue counts double: an item a week past a three-day interval is not merely
        // waiting, it has probably been forgotten.
        double badly = (double) inputs.reviewsOverdue() / inputs.reviewsTracked();
        return 100.0 * Math.clamp(1.0 - slipped - badly, 0.0, 1.0);
    }

    /** Are you actually studying? Study days in the last fortnight against the target. */
    static double consistency(ReadinessInputs inputs) {
        return 100.0 * Math.clamp(
                (double) inputs.studyDaysLast14() / inputs.targetStudyDays(), 0.0, 1.0);
    }

    /**
     * Does it hold up under exam conditions?
     *
     * <p>Recency-decayed best of the recent mocks. Best rather than mean, because a mock sat
     * while ill is not evidence about what you know; decayed, because a strong mock from two
     * months ago is not evidence about today.
     */
    static double mock(ReadinessInputs inputs) {
        if (inputs.recentMockScores().isEmpty()) {
            return 0;
        }

        double best = 0;
        List<Double> scores = inputs.recentMockScores();
        List<Integer> ages = inputs.mockAges();

        for (int i = 0; i < Math.min(3, scores.size()); i++) {
            int ageDays = i < ages.size() ? Math.max(0, ages.get(i)) : 0;
            // Halves every three weeks. A mock from a fortnight ago still counts for most of
            // itself; one from the spring barely counts at all.
            double decay = Math.pow(0.5, ageDays / 21.0);
            best = Math.max(best, Math.clamp(scores.get(i), 0.0, 100.0) * decay);
        }
        return best;
    }

    /* ------------------------------------------------------------------ confidence */

    /**
     * How much the score is allowed to claim.
     *
     * <p>After twelve questions nobody can be 88% ready for anything, and a system that says so
     * is lying at the exact moment the stakes are highest. This caps an early score near 55-60%
     * and lifts the ceiling as evidence accumulates. It costs nothing and is the difference
     * between a number worth acting on and a vanity metric.
     */
    static double confidenceFactor(int totalAttempts) {
        double progress = Math.min(1.0, (double) Math.max(0, totalAttempts) / FULL_CONFIDENCE_ATTEMPTS);
        return Math.min(1.0, CONFIDENCE_FLOOR_FACTOR + (1.0 - CONFIDENCE_FLOOR_FACTOR) * progress);
    }

    /* ------------------------------------------------------------------ explanation */

    private static Map<String, Double> deltas(Map<String, Double> components, double score,
                                              Map<String, Double> previous) {
        Map<String, Double> deltas = new LinkedHashMap<>();
        if (previous == null || previous.isEmpty()) {
            return deltas;
        }

        components.forEach((key, value) -> {
            Double was = previous.get(key);
            if (was != null) {
                deltas.put(key, round(value - was));
            }
        });

        Double previousTotal = previous.get("total");
        if (previousTotal != null) {
            deltas.put("total", round(score - previousTotal));
        }
        return deltas;
    }

    /**
     * The changes worth reading, largest first.
     *
     * <p>Filtered by size rather than listed exhaustively: six lines of "coverage +0.1" teaches
     * a learner to skip the whole section, and then the one line that mattered goes unread too.
     */
    private static List<String> drivers(Map<String, Double> deltas, ReadinessInputs inputs) {
        List<String> drivers = new ArrayList<>();

        deltas.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("total"))
                .filter(entry -> Math.abs(entry.getValue()) >= REPORTABLE_DELTA)
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<String, Double> entry) -> Math.abs(entry.getValue())).reversed())
                .limit(3)
                .forEach(entry -> drivers.add("%s %s%.1f".formatted(
                        label(entry.getKey()),
                        entry.getValue() >= 0 ? "+" : "",
                        entry.getValue())));

        if (inputs.reviewsDue() > 0) {
            drivers.add("%d review%s waiting".formatted(
                    inputs.reviewsDue(), inputs.reviewsDue() == 1 ? "" : "s"));
        }
        return drivers;
    }

    /**
     * The most valuable thing to do next.
     *
     * <p>The weakest component by <em>weighted</em> shortfall, not by raw score: being at 40% on
     * something worth a tenth of the total matters less than being at 60% on something worth a
     * quarter, and telling the learner otherwise would send them to the wrong place.
     */
    private static Lever biggestLever(Map<String, Double> components,
                                      Map<String, Double> weights, ReadinessInputs inputs) {
        String worst = null;
        double worstGain = -1;

        for (Map.Entry<String, Double> entry : components.entrySet()) {
            double shortfall = 100.0 - entry.getValue();
            double gain = shortfall * weights.getOrDefault(entry.getKey(), 0.0);
            if (gain > worstGain) {
                worstGain = gain;
                worst = entry.getKey();
            }
        }

        if (worst == null) {
            return new Lever("accuracy", "Answer some questions to get started", 0);
        }
        // Only part of the shortfall is realistically closable in the near term, and promising
        // the whole of it would make the estimate a number nobody believes twice.
        return new Lever(worst, actionFor(worst, inputs), round(worstGain * 0.4));
    }

    private static String actionFor(String component, ReadinessInputs inputs) {
        return switch (component) {
            case "coverage" -> "Work through topics you have barely touched — "
                    + "a few answers each turns unmeasured into measured";
            case "accuracy" -> "Practise your weakest subjects; the engine will serve them";
            case "depth" -> "Take on harder questions — you are answering below the level "
                    + "the real thing will demand";
            case "retention" -> inputs.reviewsDue() > 0
                    ? "Clear the %d review%s waiting".formatted(
                            inputs.reviewsDue(), inputs.reviewsDue() == 1 ? "" : "s")
                    : "Keep reviewing, so what you know stays known";
            case "consistency" -> "Study on more days — short and regular beats long and rare";
            case "mock" -> inputs.recentMockScores().isEmpty()
                    ? "Sit a mock exam; nothing else tells you how it holds up under pressure"
                    : "Sit another mock — the last one is getting old";
            default -> "Keep practising";
        };
    }

    private static String headline(double score, ConfidenceBand band, ReadinessInputs inputs) {
        if (band == ConfidenceBand.CALIBRATING) {
            return "Still calibrating — %d of about %d answers".formatted(
                    inputs.totalAttempts(), FULL_CONFIDENCE_ATTEMPTS);
        }
        return "%d%% ready · confidence %s".formatted(
                Math.round(score), band.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String label(String component) {
        return component.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + component.substring(1);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** Weights are fractions of one, so they need enough precision to still sum to one. */
    private static double roundWeight(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /** Matching the scale the snapshot column stores, so the stored and reported values agree. */
    private static double roundConfidence(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private static Map<String, Double> round(Map<String, Double> values) {
        Map<String, Double> rounded = new LinkedHashMap<>();
        values.forEach((key, value) -> rounded.put(key, round(value)));
        return rounded;
    }
}
