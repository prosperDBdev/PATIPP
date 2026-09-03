package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-answer multiple choice: exactly one option is correct.
 *
 * @param shuffle whether to randomise option order when served. On by default, because a
 *                bank whose correct answer is usually option C teaches position, not content.
 */
public record McqContent(List<Option> options, boolean shuffle) implements QuestionContent {

    public McqContent {
        options = List.copyOf(options);
    }

    static McqContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);

        List<Option> options = Option.parseAll(reader);
        boolean shuffle = reader.optionalBoolean("shuffle", true);

        long correctCount = options.stream().filter(Option::correct).count();
        if (!options.isEmpty() && correctCount != 1) {
            reader.reject("options", correctCount == 0
                    ? "exactly one option must be marked correct, but none is"
                    : "exactly one option must be marked correct, but " + correctCount + " are");
        }

        reader.throwIfInvalid();
        return new McqContent(options, shuffle);
    }

    @Override
    public QuestionType type() {
        return QuestionType.MCQ;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("options", Option.toMaps(options));
        payload.put("shuffle", shuffle);
        return payload;
    }

    public String correctOptionId() {
        return options.stream().filter(Option::correct).findFirst()
                .map(Option::id)
                .orElseThrow(() -> new IllegalStateException(
                        "an MCQ without a correct option cannot be constructed"));
    }

    @Override
    public EvaluationResult evaluate(Answer answer) {
        if (!(answer instanceof Answer.Choice choice)) {
            return EvaluationResult.wrong("Expected a single selected option.");
        }
        if (choice.optionIds().size() != 1) {
            return EvaluationResult.wrong("Select exactly one option.");
        }
        return choice.optionIds().contains(correctOptionId())
                ? EvaluationResult.right()
                : EvaluationResult.wrong(null);
    }

    @Override
    public String fingerprint() {
        // Option text, not ids: two imports of the same question may assign different ids,
        // and they are still the same question.
        return options.stream().map(Option::text).sorted().reduce("", (a, b) -> a + "|" + b);
    }
}
