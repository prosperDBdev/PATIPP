package com.patipp.questions.domain.content;

import java.util.Set;

/**
 * What a learner submitted, in the shape the question format expects.
 *
 * <p>Sealed with nested records rather than a single bag of nullable fields. A bag would
 * compile happily when a controller passed selected options to a true/false question; this
 * cannot even be expressed.
 *
 * <p>Pure: no framework types, so evaluation can be unit-tested without a container.
 */
public sealed interface Answer {

    /** One or more chosen option ids. Used by MCQ (exactly one) and multi-select. */
    record Choice(Set<String> optionIds) implements Answer {
        public Choice {
            optionIds = optionIds == null ? Set.of() : Set.copyOf(optionIds);
        }
    }

    /** Free text, for short answer. */
    record Text(String value) implements Answer {
        public Text {
            value = value == null ? "" : value;
        }
    }

    record Bool(boolean value) implements Answer {
    }

    /**
     * A self-reported recall grade for a flashcard: 1 Again, 2 Hard, 3 Good, 4 Easy.
     * Fed to the spaced-repetition scheduler in Phase 6.
     */
    record Grade(int value) implements Answer {
        public Grade {
            if (value < 1 || value > 4) {
                throw new IllegalArgumentException("grade must be between 1 and 4, was " + value);
            }
        }
    }
}
