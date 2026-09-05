package com.patipp.adaptive;

import com.patipp.adaptive.LearnerModel.TopicKey;
import com.patipp.adaptive.LearnerModel.TopicState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks what to work on, and refuses to guess.
 *
 * <p>Four signals rather than accuracy alone, because accuracy alone is misleading in both
 * directions: a topic at 60% that you got wrong an hour ago needs attention more urgently
 * than one at 60% you have not touched in a month, and a topic at 85% where you have seen
 * two questions out of forty is not understood, only sampled.
 */
public final class WeaknessDetector {

    public static final String VERSION = "WEAKNESS_V1";

    private static final double W_ACCURACY = 0.45;
    private static final double W_RECENCY = 0.25;
    private static final double W_COVERAGE = 0.15;
    private static final double W_STREAK = 0.15;

    /** How fast the sting of a recent mistake fades, in days. */
    private static final double ERROR_HALF_LIFE_DAYS = 5.0;

    public String version() {
        return VERSION;
    }

    /**
     * Every measured bucket, worst first.
     *
     * <p>Buckets below the confidence floor are excluded rather than ranked low: they belong
     * in {@link #needsAssessment}, which is a different recommendation.
     */
    public List<Weakness> rank(LearnerModel model, Instant now) {
        List<Weakness> ranked = new ArrayList<>();

        for (TopicState state : model.topics().values()) {
            if (!state.isAssessed()) {
                continue;
            }
            ranked.add(new Weakness(state.key(), score(state, now), state.decayedAccuracy(),
                    state.attempts(), state.level()));
        }

        ranked.sort(Comparator.comparingDouble(Weakness::score).reversed());
        return List.copyOf(ranked);
    }

    /**
     * Buckets that have been touched but not enough to judge, least-attempted first.
     *
     * <p>Surfaced separately and deliberately: "you have not measured this yet" is a more
     * useful and more honest instruction than "you are bad at this".
     */
    public List<Weakness> needsAssessment(LearnerModel model) {
        List<Weakness> unassessed = new ArrayList<>();

        for (TopicState state : model.topics().values()) {
            if (state.isAssessed()) {
                continue;
            }
            unassessed.add(new Weakness(state.key(), 0.0, state.decayedAccuracy(),
                    state.attempts(), state.level()));
        }

        unassessed.sort(Comparator.comparingInt(Weakness::attempts));
        return List.copyOf(unassessed);
    }

    /**
     * How much this bucket needs work, on 0-1.
     *
     * <p>Weighted by the topic's own weight at the end, so being weak at something worth
     * thirty percent of the paper outranks being weak at something worth five.
     */
    double score(TopicState state, Instant now) {
        double accuracyTerm = 1.0 - Math.clamp(state.decayedAccuracy(), 0.0, 1.0);
        double recencyTerm = errorRecency(state.lastIncorrectAt(), now);
        double coverageTerm = 1.0 - Math.clamp(state.coverage(), 0.0, 1.0);
        // Two wrong in a row is a much louder signal than the same two spread over a month.
        double streakTerm = Math.clamp(state.consecutiveWrong() / 3.0, 0.0, 1.0);

        double raw = W_ACCURACY * accuracyTerm
                + W_RECENCY * recencyTerm
                + W_COVERAGE * coverageTerm
                + W_STREAK * streakTerm;

        double weight = state.weight() <= 0 ? 1.0 : state.weight();
        return Math.clamp(raw * Math.min(2.0, weight), 0.0, 1.0);
    }

    /** 1.0 for a mistake just now, halving every five days, 0.0 if there has never been one. */
    private static double errorRecency(Instant lastIncorrectAt, Instant now) {
        if (lastIncorrectAt == null) {
            return 0.0;
        }
        double days = Duration.between(lastIncorrectAt, now).toMillis() / 86_400_000.0;
        if (days < 0) {
            return 1.0;
        }
        return Math.pow(0.5, days / ERROR_HALF_LIFE_DAYS);
    }

    /**
     * @param score 0-1, higher means more in need of work
     */
    public record Weakness(TopicKey topic, double score, double decayedAccuracy,
                           int attempts, MasteryLevel level) {
    }
}
