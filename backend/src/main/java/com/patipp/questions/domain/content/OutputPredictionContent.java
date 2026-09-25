package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A snippet of code, and what it prints.
 *
 * <p>Fully auto-gradable with no execution anywhere: the author states the output, the
 * learner types what they think it is, and the two strings are compared. That makes it the
 * cheapest possible way to test whether someone can actually trace code rather than recognise
 * its shape - which is most of what a technical screen is really asking.
 *
 * <p>Nothing here runs the code. Running untrusted code is a genuine security surface and
 * would put a sandbox underneath a core feature; predicting output needs neither.
 */
public record OutputPredictionContent(
        String language,
        String code,
        String expectedOutput,
        MatchMode matchMode) implements QuestionContent {

    public enum MatchMode {
        /** Character for character. For questions where whitespace is the point. */
        EXACT,
        /**
         * Trailing whitespace and blank lines at the ends are forgiven, everything else is
         * compared exactly. The sensible default: nobody is testing whether you remembered
         * the final newline.
         */
        TRIMMED,
        /** Whitespace and case insensitive, for output where only the values matter. */
        LOOSE
    }

    private static final int MAX_CODE = 8000;
    private static final int MAX_OUTPUT = 4000;

    static OutputPredictionContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);

        String code = reader.requireVerbatim("code", MAX_CODE);
        String expected = reader.requireVerbatim("expectedOutput", MAX_OUTPUT);
        String language = reader.optionalString("language", 40);

        String rawMode = reader.optionalString("matchMode", 20);
        MatchMode mode = MatchMode.TRIMMED;
        if (rawMode != null) {
            try {
                mode = MatchMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                reader.reject("matchMode", "must be one of EXACT, TRIMMED or LOOSE");
            }
        }

        reader.throwIfInvalid();
        return new OutputPredictionContent(
                language == null ? "text" : language.strip(), code, expected, mode);
    }

    @Override
    public QuestionType type() {
        return QuestionType.OUTPUT_PREDICTION;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", language);
        payload.put("code", code);
        payload.put("expectedOutput", expectedOutput);
        payload.put("matchMode", matchMode.name());
        return payload;
    }

    /**
     * The code and the language, never the output.
     *
     * <p>The snippet has to be shown - it <em>is</em> the question. The expected output is the
     * answer key and stays on the server.
     */
    @Override
    public Map<String, Object> presentation() {
        return Map.of("language", language, "code", code, "matchMode", matchMode.name());
    }

    @Override
    public Map<String, Object> correctAnswer() {
        return Map.of("expectedOutput", expectedOutput);
    }

    @Override
    public EvaluationResult evaluate(Answer answer) {
        if (!(answer instanceof Answer.Text text)) {
            return EvaluationResult.wrong("Expected the predicted output as text.");
        }
        if (text.value().isBlank()) {
            return EvaluationResult.wrong("Nothing was written.");
        }

        boolean matched = switch (matchMode) {
            case EXACT -> expectedOutput.equals(text.value());
            case TRIMMED -> trimLines(expectedOutput).equals(trimLines(text.value()));
            case LOOSE -> loosen(expectedOutput).equals(loosen(text.value()));
        };

        if (matched) {
            return EvaluationResult.right();
        }

        // Says how it differs without saying what the answer is. "Close, but the wrong number
        // of lines" is a genuine hint towards tracing the code again; the output itself is not.
        int expectedLines = trimLines(expectedOutput).split("\n", -1).length;
        int gotLines = trimLines(text.value()).split("\n", -1).length;
        return EvaluationResult.wrong(expectedLines == gotLines
                ? "Right number of lines, wrong content."
                : "Expected %d line%s of output, got %d."
                        .formatted(expectedLines, expectedLines == 1 ? "" : "s", gotLines));
    }

    /** Drops trailing whitespace from each line and blank lines at either end. */
    static String trimLines(String value) {
        List<String> lines = Arrays.asList(value.replace("\r\n", "\n").split("\n", -1));
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isBlank()) {
            from++;
        }
        while (to > from && lines.get(to - 1).isBlank()) {
            to--;
        }
        return String.join("\n", lines.subList(from, to).stream()
                .map(line -> line.replaceAll("\\s+$", ""))
                .toList());
    }

    private static String loosen(String value) {
        return value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String fingerprint() {
        return trimLines(code) + "||" + trimLines(expectedOutput);
    }
}
