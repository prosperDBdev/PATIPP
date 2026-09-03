package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.LinkedHashMap;
import java.util.Map;

/** A statement that is either true or false. */
public record TrueFalseContent(boolean answer) implements QuestionContent {

    static TrueFalseContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);
        boolean answer = reader.requireBoolean("answer");
        reader.throwIfInvalid();
        return new TrueFalseContent(answer);
    }

    @Override
    public QuestionType type() {
        return QuestionType.TRUE_FALSE;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", answer);
        return payload;
    }

    @Override
    public EvaluationResult evaluate(Answer submitted) {
        if (!(submitted instanceof Answer.Bool bool)) {
            return EvaluationResult.wrong("Expected true or false.");
        }
        return bool.value() == answer ? EvaluationResult.right() : EvaluationResult.wrong(null);
    }

    @Override
    public String fingerprint() {
        // The stem alone identifies a true/false question; the answer is not part of what
        // makes it a distinct question, so a re-import with a corrected answer is caught as
        // a duplicate rather than silently creating a contradictory second copy.
        return "";
    }
}
