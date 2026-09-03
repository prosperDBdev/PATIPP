package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * Parses a submitted answer into the shape the format expects.
     *
     * <p>Mirrors {@link QuestionContent#parse}: untrusted JSON goes in, a checked type comes
     * out, and everything past this point can rely on the shape. The expected field is named
     * per format - {@code optionIds}, {@code value}, {@code text}, {@code grade} - so a
     * mismatch is reported against the field the client actually got wrong.
     *
     * @throws ContentValidationException when the payload does not fit the format
     */
    static Answer parse(QuestionType type, Map<String, Object> payload) {
        Map<String, Object> body = payload == null ? Map.of() : payload;

        return switch (type) {
            case MCQ, MULTI_SELECT -> new Choice(readOptionIds(body));
            case TRUE_FALSE -> new Bool(readBoolean(body));
            case SHORT_ANSWER -> new Text(readText(body));
            case FLASHCARD -> new Grade(readGrade(body));
            // Exhaustive by construction: a new format that forgets to say how its answers
            // arrive will not compile.
            case LONG_ANSWER, CODING, DEBUGGING, OUTPUT_PREDICTION, SCENARIO, BEHAVIORAL ->
                    throw new ContentValidationException(List.of(new ContentValidationException
                            .FieldError("type", type + " cannot be answered yet")));
        };
    }

    private static Set<String> readOptionIds(Map<String, Object> body) {
        Object raw = body.get("optionIds");
        if (!(raw instanceof List<?> list)) {
            throw reject("optionIds", "must be a list of selected option ids");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                ids.add(item.toString().strip());
            }
        }
        return ids;
    }

    private static boolean readBoolean(Map<String, Object> body) {
        Object raw = body.get("value");
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        throw reject("value", "must be true or false");
    }

    private static String readText(Map<String, Object> body) {
        Object raw = body.get("text");
        if (raw == null) {
            throw reject("text", "is required");
        }
        return raw.toString();
    }

    private static int readGrade(Map<String, Object> body) {
        Object raw = body.get("grade");
        try {
            int value = raw instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(raw).strip());
            if (value < 1 || value > 4) {
                throw reject("grade", "must be between 1 (Again) and 4 (Easy)");
            }
            return value;
        } catch (NumberFormatException notANumber) {
            throw reject("grade", "must be between 1 (Again) and 4 (Easy)");
        }
    }

    private static ContentValidationException reject(String field, String message) {
        return new ContentValidationException(
                new ArrayList<>(List.of(new ContentValidationException.FieldError(field, message))));
    }
}
