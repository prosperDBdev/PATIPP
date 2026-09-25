package com.patipp.scheduling;

/**
 * How well something was recalled.
 *
 * <p>Four levels rather than right/wrong, because "I got there eventually" and "I knew it
 * instantly" should not be scheduled the same way. A flashcard reports this directly; for
 * every other format {@link GradeDerivation} infers it, so the scheduler has one input rather
 * than a branch per question type.
 */
public enum Grade {

    /** Could not recall it. Resets the interval and counts as a lapse. */
    AGAIN(1),

    /** Recalled, but with effort or slowly. Grows the interval only a little. */
    HARD(2),

    /** Recalled. The normal case, and the one the model is tuned around. */
    GOOD(3),

    /** Instant. Grows the interval fastest, because the current one was clearly too short. */
    EASY(4);

    private final int value;

    Grade(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public boolean isLapse() {
        return this == AGAIN;
    }

    public static Grade of(int value) {
        return switch (value) {
            case 1 -> AGAIN;
            case 2 -> HARD;
            case 3 -> GOOD;
            case 4 -> EASY;
            default -> throw new IllegalArgumentException(
                    "grade must be between 1 and 4, was " + value);
        };
    }
}
