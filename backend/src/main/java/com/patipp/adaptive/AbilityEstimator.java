package com.patipp.adaptive;

/**
 * Moves the learner's ability and the question's difficulty after an answer.
 *
 * <p>An interface with a {@link #version()} because this will be replaced. Every rating the
 * engine writes is stamped with the version that produced it, so when a second implementation
 * lands it is always possible to say which algorithm generated a given number - and the replay
 * harness can score the two against the same history.
 */
public interface AbilityEstimator {

    String version();

    /**
     * @param learnerRating   the learner's current Elo in the topic
     * @param itemRating      the question's current Elo
     * @param outcome         1.0 for correct, 0.0 for wrong, or the partial-credit score in
     *                        between - a multi-select answered two of three right is 0.667 and
     *                        should move the ratings by two thirds, not by all or nothing
     * @param attemptsInTopic how much history the learner has here, which decides how far the
     *                        estimate is allowed to move
     * @param itemRatingCount how many times this question has been rated
     */
    Update update(double learnerRating, double itemRating, double outcome,
                  int attemptsInTopic, int itemRatingCount);

    /**
     * @param expectation what the estimator predicted before seeing the outcome, kept so
     *                    calibration can be measured afterwards
     */
    record Update(double learnerRating, double itemRating, double expectation) {
    }
}
