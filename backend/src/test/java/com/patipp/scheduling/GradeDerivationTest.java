package com.patipp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning an ordinary quiz answer into a recall grade.
 *
 * <p>The behaviour worth protecting here is one rule: a correct answer that took far too long,
 * or that the learner reported doubt about, must not be scheduled as knowledge. Get that wrong
 * and the item vanishes for three weeks on the strength of a guess, which is the single most
 * common way spaced repetition quietly stops working.
 */
class GradeDerivationTest {

    /** 60 seconds expected, no observed mean yet. */
    private Grade derive(boolean correct, Integer ms, Integer confidence) {
        return GradeDerivation.derive(correct, ms, confidence, 60, null, 0);
    }

    @Test
    @DisplayName("a wrong answer is Again, whatever else was true of it")
    void wrongIsAlwaysAgain() {
        assertThat(derive(false, 1_000, 4)).isEqualTo(Grade.AGAIN);
        assertThat(derive(false, 500_000, 1)).isEqualTo(Grade.AGAIN);
    }

    @Test
    @DisplayName("right, at a normal pace, is Good")
    void normalPaceIsGood() {
        assertThat(derive(true, 45_000, null)).isEqualTo(Grade.GOOD);
    }

    @Test
    @DisplayName("right but far too slow is Hard, because that was not recall")
    void slowIsHard() {
        // Two minutes on a question estimated at one. Getting there eventually is a different
        // thing from knowing it, and scheduling it as the same thing loses the distinction.
        assertThat(derive(true, 150_000, null)).isEqualTo(Grade.HARD);
    }

    @Test
    @DisplayName("right and instant is Easy")
    void fastIsEasy() {
        assertThat(derive(true, 20_000, null)).isEqualTo(Grade.EASY);
    }

    @Test
    @DisplayName("reported doubt outranks a fast answer")
    void lowConfidenceBeatsSpeed() {
        // Answered in a flash and reported as a guess. The learner knows better than the clock,
        // and this is exactly the lucky guess the whole rule exists to catch.
        assertThat(derive(true, 5_000, 1)).isEqualTo(Grade.HARD);
        assertThat(derive(true, 5_000, 2)).isEqualTo(Grade.HARD);
    }

    @Test
    @DisplayName("a fast answer with middling confidence is Good, not Easy")
    void unsureFastIsOnlyGood() {
        assertThat(derive(true, 10_000, 3)).isEqualTo(Grade.GOOD);
        assertThat(derive(true, 10_000, 4)).isEqualTo(Grade.EASY);
    }

    @Test
    @DisplayName("with no timing at all, Good rather than Easy")
    void missingTimingIsConservative() {
        // Absent evidence is not evidence of mastery, and over-scheduling costs more than
        // under-scheduling: one wastes a few seconds, the other loses the fact.
        assertThat(derive(true, null, null)).isEqualTo(Grade.GOOD);
        assertThat(derive(true, null, 4)).isEqualTo(Grade.GOOD);
    }

    @Test
    @DisplayName("the observed mean replaces the author's estimate once there is enough of it")
    void observedMeanWinsEventually() {
        // Authored at 10 seconds, but everyone actually takes 100. A 40-second answer is then
        // comfortably fast rather than four times too slow.
        assertThat(GradeDerivation.derive(true, 40_000, null, 10, 100_000, 50))
                .isEqualTo(Grade.EASY);

        // With only three samples behind it, the author's estimate is still better evidence,
        // and the same answer reads as a struggle.
        assertThat(GradeDerivation.derive(true, 40_000, null, 10, 100_000, 3))
                .isEqualTo(Grade.HARD);
    }

    @Test
    @DisplayName("a nonsensical estimate cannot make everything Easy")
    void estimateHasAFloor() {
        // Zero estimated seconds would otherwise make any answer infinitely slow, or with a
        // naive guard infinitely fast. Neither is a grade anyone should receive.
        assertThat(GradeDerivation.derive(true, 400, null, 0, null, 0)).isEqualTo(Grade.EASY);
        assertThat(GradeDerivation.derive(true, 30_000, null, 0, null, 0)).isEqualTo(Grade.HARD);
    }

    @Test
    @DisplayName("every derived grade is one the scheduler accepts")
    void gradesRoundTrip() {
        for (Grade grade : Grade.values()) {
            assertThat(Grade.of(grade.value())).isEqualTo(grade);
        }
    }
}
