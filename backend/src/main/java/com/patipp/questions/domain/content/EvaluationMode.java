package com.patipp.questions.domain.content;

/**
 * Who decides whether an answer was right.
 *
 * <p>Recorded on every attempt, because a score is only meaningful alongside how it was
 * arrived at. Averaging a self-graded coding problem together with an auto-graded
 * multiple-choice question is legitimate - both are evidence - but a readiness figure that
 * cannot say how much of itself came from self-assessment is a figure nobody can audit.
 */
public enum EvaluationMode {

    /** The server graded it from the answer key. */
    AUTO,

    /**
     * The learner graded their own work against a rubric.
     *
     * <p>Used where the platform cannot judge: a coding problem solved by hand in an editor.
     * Deliberately not treated as inferior evidence - an honest self-assessment against a
     * written rubric is a great deal better than not practising the thing at all, which is
     * the real alternative.
     */
    SELF,

    /**
     * Part auto, part self.
     *
     * <p>Debugging: the platform can check which line you pointed at, but not whether your
     * explanation of why was any good.
     */
    MIXED
}
