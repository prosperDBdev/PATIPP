package com.patipp.adaptive;

/**
 * Elo with a decaying K-factor on the learner side and a small, stabilising one on the item.
 *
 * <p>The two sides move at deliberately different speeds. A learner really does get better,
 * so their rating should stay responsive; a question does not change, so its rating should
 * settle once enough people have met it. Using one K for both would either make the learner
 * estimate sluggish or let a single unlucky answer rewrite a well-measured question.
 */
public final class EloAbilityEstimator implements AbilityEstimator {

    public static final String VERSION = "ELO_V1";

    /**
     * How far one answer may move the learner's rating, by how much history they have.
     *
     * <p>Early answers move it a long way because there is nothing better to go on; later
     * ones refine rather than overturn. Without the decay, question 300 would swing the
     * estimate as violently as question 3, and the number would never settle.
     */
    static double learnerK(int attemptsInTopic) {
        if (attemptsInTopic < 10) {
            return 48.0;
        }
        if (attemptsInTopic < 30) {
            return 32.0;
        }
        return attemptsInTopic < 100 ? 20.0 : 12.0;
    }

    /** Small from the start, and smaller once the question is well measured. */
    static double itemK(int itemRatingCount) {
        return itemRatingCount < 30 ? 8.0 : 4.0;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Update update(double learnerRating, double itemRating, double outcome,
                         int attemptsInTopic, int itemRatingCount) {
        double expectation = Elo.expectation(learnerRating, itemRating);
        double score = Math.clamp(outcome, 0.0, 1.0);

        // The learner gains what the item loses. Beating a hard question moves both a long
        // way; getting an easy one right barely moves either, which is correct - it was not
        // evidence of much.
        double surprise = score - expectation;

        return new Update(
                Elo.clamp(learnerRating + learnerK(attemptsInTopic) * surprise),
                Elo.clamp(itemRating - itemK(itemRatingCount) * surprise),
                expectation);
    }
}
