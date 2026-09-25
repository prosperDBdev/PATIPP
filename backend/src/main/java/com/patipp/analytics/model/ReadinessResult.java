package com.patipp.analytics.model;

import java.util.List;
import java.util.Map;

/**
 * A readiness score, and everything needed to account for it.
 *
 * <p>The breakdown is not decoration. A bare percentage cannot explain why it moved, so nobody
 * can act on it and eventually nobody believes it. Storing the components, the weights, the
 * deltas and the drivers is what makes "readiness fell three points because fourteen items are
 * overdue — clearing them is worth about four" a sentence the application can actually produce.
 *
 * @param score       0-100, after the confidence factor has been applied
 * @param raw         0-100, before it. Kept because the difference between the two <em>is</em>
 *                    the explanation for an early score that looks low
 * @param confidence  0-1 multiplier reflecting how much evidence exists
 * @param components  each of the six, 0-100
 * @param weights     what each was multiplied by, so a score can be recomputed by hand
 * @param deltas      change since the previous snapshot, by component, plus "total"
 * @param drivers     the changes worth reading, largest first, in plain sentences
 * @param biggestLever the single most valuable thing to do next, and what it is worth
 */
public record ReadinessResult(
        String version,
        double score,
        double raw,
        double confidence,
        ConfidenceBand band,
        Map<String, Double> components,
        Map<String, Double> weights,
        Map<String, Double> deltas,
        List<String> drivers,
        Lever biggestLever,
        String headline) {

    public ReadinessResult {
        components = components == null ? Map.of() : Map.copyOf(components);
        weights = weights == null ? Map.of() : Map.copyOf(weights);
        deltas = deltas == null ? Map.of() : Map.copyOf(deltas);
        drivers = drivers == null ? List.of() : List.copyOf(drivers);
    }

    /**
     * How much the number should be trusted, in words.
     *
     * <p>Shown alongside the score rather than folded into it silently. A learner who sees
     * "62%, confidence low — 18 of about 150 answers" knows what to do with it; one who sees
     * only 62% does not.
     */
    public enum ConfidenceBand {
        /** Too little evidence to report a score at all. */
        CALIBRATING,
        LOW,
        MODERATE,
        HIGH;

        static ConfidenceBand of(int totalAttempts) {
            if (totalAttempts < 12) {
                return CALIBRATING;
            }
            if (totalAttempts < 50) {
                return LOW;
            }
            return totalAttempts < 150 ? MODERATE : HIGH;
        }
    }

    /**
     * The most valuable next action.
     *
     * <p>The single most useful thing the whole analytics module produces, and the reason the
     * components are scored separately rather than averaged: knowing that retention is the
     * weakest of the six tells you what to do, where an overall 71% does not.
     *
     * @param estimatedGain points of readiness this is worth, roughly
     */
    public record Lever(String component, String action, double estimatedGain) {
    }
}
