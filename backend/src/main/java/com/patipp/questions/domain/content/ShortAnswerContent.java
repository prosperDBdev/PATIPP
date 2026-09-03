package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A typed free-text answer, graded automatically against accepted answers.
 *
 * <p>Automatic grading of free text is a trade-off, so the strictness is the author's
 * choice rather than a hidden default. {@code NORMALIZED} is the sensible middle: it forgives
 * case, spacing, accents and trailing punctuation, which are almost never what is being
 * tested, while still requiring the right words.
 */
public record ShortAnswerContent(
        List<String> acceptedAnswers,
        MatchMode matchMode,
        List<String> requiredKeywords) implements QuestionContent {

    public enum MatchMode {
        /** Character-for-character after trimming. For things like exact syntax. */
        EXACT,
        /** Case, accent, punctuation and whitespace insensitive. The default. */
        NORMALIZED,
        /** Correct when every required keyword appears somewhere in the response. */
        KEYWORDS
    }

    private static final int MAX_ANSWERS = 20;
    private static final int MAX_LENGTH = 500;

    public ShortAnswerContent {
        acceptedAnswers = List.copyOf(acceptedAnswers);
        requiredKeywords = List.copyOf(requiredKeywords);
    }

    static ShortAnswerContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);

        List<String> accepted = reader.stringList("acceptedAnswers", MAX_ANSWERS, MAX_LENGTH);
        List<String> keywords = reader.stringList("requiredKeywords", MAX_ANSWERS, MAX_LENGTH);

        String rawMode = reader.optionalString("matchMode", 20);
        MatchMode mode = MatchMode.NORMALIZED;
        if (rawMode != null) {
            try {
                mode = MatchMode.valueOf(rawMode.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                reader.reject("matchMode", "must be one of EXACT, NORMALIZED or KEYWORDS");
            }
        }

        if (mode == MatchMode.KEYWORDS) {
            if (keywords.isEmpty()) {
                reader.reject("requiredKeywords", "is required when matchMode is KEYWORDS");
            }
        } else if (accepted.isEmpty()) {
            reader.reject("acceptedAnswers", "must contain at least one accepted answer");
        }

        reader.throwIfInvalid();
        return new ShortAnswerContent(accepted, mode, keywords);
    }

    @Override
    public QuestionType type() {
        return QuestionType.SHORT_ANSWER;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("acceptedAnswers", acceptedAnswers);
        payload.put("matchMode", matchMode.name());
        payload.put("requiredKeywords", requiredKeywords);
        return payload;
    }

    @Override
    public EvaluationResult evaluate(Answer answer) {
        if (!(answer instanceof Answer.Text text)) {
            return EvaluationResult.wrong("Expected a written answer.");
        }
        String response = text.value().strip();
        if (response.isEmpty()) {
            return EvaluationResult.wrong("Nothing was written.");
        }

        return switch (matchMode) {
            case EXACT -> acceptedAnswers.contains(response)
                    ? EvaluationResult.right()
                    : EvaluationResult.wrong(null);

            case NORMALIZED -> {
                String normalised = normalise(response);
                boolean matched = acceptedAnswers.stream()
                        .anyMatch(accepted -> normalise(accepted).equals(normalised));
                yield matched ? EvaluationResult.right() : EvaluationResult.wrong(null);
            }

            case KEYWORDS -> {
                String normalised = normalise(response);
                List<String> missing = requiredKeywords.stream()
                        .filter(keyword -> !normalised.contains(normalise(keyword)))
                        .toList();
                yield missing.isEmpty()
                        ? EvaluationResult.right()
                        : EvaluationResult.wrong("Missing: " + String.join(", ", missing));
            }
        };
    }

    /**
     * Lower-cases, strips accents and punctuation, collapses whitespace, and drops a leading
     * English article.
     *
     * <p>The point is to forgive everything that is not the knowledge being tested. Someone
     * who writes "The Event Loop." knows what "event loop" means, and marking them wrong
     * teaches them to distrust the app rather than to learn the topic.
     *
     * <p>The leading article is where this stops, deliberately. Stemming, synonyms and edit
     * distance all sound helpful and all make grading unpredictable: an author can no longer
     * tell by reading a question which answers it will accept. Anything looser than this
     * belongs behind {@link MatchMode#KEYWORDS}, where the author says explicitly what must
     * appear.
     */
    static String normalise(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String cleaned = decomposed
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return cleaned.replaceFirst("^(the|a|an) ", "");
    }

    @Override
    public String fingerprint() {
        return acceptedAnswers.stream().map(ShortAnswerContent::normalise).sorted()
                .reduce("", (a, b) -> a + "|" + b);
    }
}
