package com.patipp.questions.domain.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One selectable choice, shared by multiple-choice and multiple-select questions.
 *
 * @param id      stable within the question. Answers reference this rather than the display
 *                text or a position, so an author can reword or reorder an option without
 *                invalidating every attempt already recorded against it.
 * @param text    what the learner reads
 * @param correct whether selecting it is right
 */
public record Option(String id, String text, boolean correct) {

    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    private static final int MAX_TEXT = 2000;

    public Option {
        id = id == null ? null : id.strip();
        text = text == null ? null : text.strip();
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("text", text);
        map.put("correct", correct);
        return map;
    }

    /**
     * Parses the shared {@code options} list and enforces the rules both formats need:
     * present, within bounds, uniquely identified, non-empty text.
     *
     * <p>Correct-answer counting differs between formats, so that check stays with each.
     */
    static List<Option> parseAll(PayloadReader reader) {
        List<Map<String, Object>> raw = reader.objectList("options", MIN_OPTIONS, MAX_OPTIONS);

        List<Option> options = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> entry = raw.get(i);
            String field = "options[" + i + "]";

            Object rawId = entry.get("id");
            // Authors writing JSON by hand rarely bother with ids; positional letters are
            // a sane default and stay stable for as long as nobody reorders the list.
            String id = (rawId == null || rawId.toString().isBlank())
                    ? String.valueOf((char) ('a' + i))
                    : rawId.toString().strip();

            if (!seenIds.add(id)) {
                reader.reject(field + ".id", "duplicates the id \"" + id + "\"");
                continue;
            }

            Object rawText = entry.get("text");
            if (rawText == null || rawText.toString().isBlank()) {
                reader.reject(field + ".text", "is required");
                continue;
            }
            String text = rawText.toString().strip();
            if (text.length() > MAX_TEXT) {
                reader.reject(field + ".text", "must be at most " + MAX_TEXT + " characters");
                continue;
            }

            Object rawCorrect = entry.get("correct");
            boolean correct = rawCorrect instanceof Boolean flag
                    ? flag
                    : Boolean.parseBoolean(String.valueOf(rawCorrect));

            options.add(new Option(id, text, correct));
        }

        return options;
    }

    static List<Map<String, Object>> toMaps(List<Option> options) {
        return options.stream().map(Option::toMap).toList();
    }
}
