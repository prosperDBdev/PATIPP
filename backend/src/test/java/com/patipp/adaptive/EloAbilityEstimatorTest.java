package com.patipp.adaptive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No Spring, no database, no clock. Every test here runs in microseconds, which is the point
 * of keeping the engine pure - the algorithm will be rewritten, and rewrites only get
 * verified properly when verifying them is free.
 */
class EloAbilityEstimatorTest {

    private final EloAbilityEstimator estimator = new EloAbilityEstimator();

    @Test
    @DisplayName("an even match is a coin flip")
    void equalRatingsGiveEvenOdds() {
        assertThat(Elo.expectation(1200, 1200)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("400 points of advantage is ten-to-one odds")
    void fourHundredPointsIsTenToOne() {
        assertThat(Elo.expectation(1600, 1200)).isCloseTo(10.0 / 11.0, within(0.001));
    }

    @Test
    @DisplayName("the 78% target sits about 220 points below the learner")
    void targetRatingMatchesTheDesign() {
        double target = Elo.ratingForExpectation(1200, 0.78);

        assertThat(1200 - target).isCloseTo(219.0, within(2.0));
        // The round trip has to hold, or the selector would be aiming at the wrong place.
        assertThat(Elo.expectation(1200, target)).isCloseTo(0.78, within(0.001));
    }

    @Test
    @DisplayName("getting a hard question right moves the learner up and the question down")
    void anUpsetMovesBothRatings() {
        AbilityEstimator.Update update = estimator.update(1200, 1400, 1.0, 50, 50);

        assertThat(update.learnerRating()).isGreaterThan(1200);
        assertThat(update.itemRating()).isLessThan(1400);
        // The learner side moves further: they are the thing that actually changes.
        assertThat(update.learnerRating() - 1200)
                .isGreaterThan(1400 - update.itemRating());
    }

    @Test
    @DisplayName("an expected win barely moves anything")
    void anExpectedResultIsWeakEvidence() {
        AbilityEstimator.Update easy = estimator.update(1600, 1000, 1.0, 50, 50);
        AbilityEstimator.Update upset = estimator.update(1600, 1000, 0.0, 50, 50);

        assertThat(easy.learnerRating() - 1600).isLessThan(1.0);
        // Losing to a question 600 points below you, however, is very informative.
        assertThat(1600 - upset.learnerRating()).isGreaterThan(15.0);
    }

    @Test
    @DisplayName("partial credit moves the ratings partially")
    void partialCreditIsNotAllOrNothing() {
        AbilityEstimator.Update partial = estimator.update(1200, 1200, 0.667, 50, 50);
        AbilityEstimator.Update full = estimator.update(1200, 1200, 1.0, 50, 50);

        assertThat(partial.learnerRating()).isGreaterThan(1200);
        assertThat(partial.learnerRating()).isLessThan(full.learnerRating());
    }

    @Test
    @DisplayName("early answers move the estimate faster than later ones")
    void kFactorDecaysWithExperience() {
        double novice = estimator.update(1200, 1200, 1.0, 3, 50).learnerRating();
        double intermediate = estimator.update(1200, 1200, 1.0, 20, 50).learnerRating();
        double veteran = estimator.update(1200, 1200, 1.0, 500, 50).learnerRating();

        assertThat(novice).isGreaterThan(intermediate);
        assertThat(intermediate).isGreaterThan(veteran);
    }

    @Test
    @DisplayName("a well-measured question stops moving so readily")
    void itemRatingStabilises() {
        double fresh = 1200 - estimator.update(1200, 1200, 1.0, 50, 5).itemRating();
        double established = 1200 - estimator.update(1200, 1200, 1.0, 50, 500).itemRating();

        assertThat(fresh).isGreaterThan(established);
    }

    @Test
    @DisplayName("a question mislabelled EASY corrects itself")
    void authoredDifficultyIsOnlyAPrior() {
        // Authored EASY, so it starts at 1000 - but it is really an expert question, and
        // strong learners keep getting it wrong.
        double rating = Elo.seedFor("EASY");
        assertThat(rating).isEqualTo(1000.0);

        int ratings = 0;
        for (int i = 0; i < 40; i++) {
            rating = estimator.update(1500, rating, 0.0, 100, ratings).itemRating();
            ratings++;
        }

        // It is now rated harder than a question authored MEDIUM, having started two bands
        // below one, and nobody relabelled anything.
        assertThat(rating).isGreaterThan(Elo.seedFor("MEDIUM"));
    }

    @Test
    @DisplayName("ratings converge on a synthetic learner of known ability")
    void convergesOnTheTruth() {
        // A learner whose real ability is 1450, answering questions of known difficulty. The
        // estimate starts wrong, at the default 1200, and has to find its way there.
        double trueAbility = 1450.0;
        double estimate = Elo.STARTING_RATING;
        Random random = new Random(42);

        for (int i = 0; i < 400; i++) {
            double itemRating = 1100 + random.nextInt(600);
            boolean correct = random.nextDouble() < Elo.expectation(trueAbility, itemRating);
            estimate = estimator.update(estimate, itemRating, correct ? 1.0 : 0.0, i, 100)
                    .learnerRating();
        }

        assertThat(estimate).isCloseTo(trueAbility, within(90.0));
    }

    @Test
    @DisplayName("the model is calibrated: predictions match outcomes")
    void brierScoreShowsCalibration() {
        // The replay harness in miniature. If the estimator's expectations are honest, the
        // Brier score - mean squared error of the predicted probability - stays well below
        // the 0.25 that always guessing 50% would give you. This is the check that turns
        // "the new algorithm feels better" into something answerable.
        double trueAbility = 1350.0;
        double estimate = Elo.STARTING_RATING;
        Random random = new Random(7);

        double squaredError = 0;
        int predictions = 0;

        for (int i = 0; i < 600; i++) {
            double itemRating = 1000 + random.nextInt(800);
            AbilityEstimator.Update update = estimator.update(
                    estimate, itemRating, 0.0, i, 100);
            double predicted = update.expectation();

            boolean correct = random.nextDouble() < Elo.expectation(trueAbility, itemRating);

            // Score the prediction before folding the outcome in, which is the only order
            // that measures foresight rather than hindsight.
            if (i > 50) {
                squaredError += Math.pow(predicted - (correct ? 1 : 0), 2);
                predictions++;
            }
            estimate = estimator.update(estimate, itemRating, correct ? 1.0 : 0.0, i, 100)
                    .learnerRating();
        }

        double brier = squaredError / predictions;
        assertThat(brier).isLessThan(0.22);
    }

    @Test
    @DisplayName("difficulty bands are relative to the learner, so HARD becomes MEDIUM")
    void bandsFollowTheLearner() {
        double question = 1400;

        assertThat(Elo.bandFor(1300, question)).isEqualTo("HARD");
        // The same question, after the learner has improved by 300 points.
        assertThat(Elo.bandFor(1600, question)).isEqualTo("MEDIUM");
        assertThat(Elo.bandFor(1800, question)).isEqualTo("EASY");
        assertThat(Elo.bandFor(1200, question)).isEqualTo("EXPERT");
    }

    @Test
    @DisplayName("ratings stay inside what the database column allows")
    void ratingsAreClamped() {
        double rating = 3999;
        for (int i = 0; i < 200; i++) {
            rating = estimator.update(3999, rating, 0.0, 1, 1).itemRating();
        }
        assertThat(rating).isBetween(Elo.MIN_RATING, Elo.MAX_RATING);
    }
}
