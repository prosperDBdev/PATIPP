package com.patipp.questions.domain.content;

/**
 * The outcome of evaluating one answer.
 *
 * @param correct whether this counts as right for accuracy statistics
 * @param score   0.0 to 1.0. Separate from {@code correct} because multi-select supports
 *                partial credit: two of three correct is 0.67 but is not a right answer,
 *                and conflating the two would quietly inflate accuracy.
 * @param note    short human explanation of a partial or zero score, or null when the
 *                result speaks for itself
 */
public record EvaluationResult(boolean correct, double score, String note) {

    public EvaluationResult {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be within 0.0 and 1.0, was " + score);
        }
    }

    // Named right() rather than correct(): a static correct() would clash with the
    // record's own correct() accessor.
    public static EvaluationResult right() {
        return new EvaluationResult(true, 1.0, null);
    }

    public static EvaluationResult wrong(String note) {
        return new EvaluationResult(false, 0.0, note);
    }

    public static EvaluationResult partial(double score, String note) {
        return new EvaluationResult(false, score, note);
    }
}
