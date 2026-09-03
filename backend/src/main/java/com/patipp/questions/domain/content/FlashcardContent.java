package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A two-sided card: prompt on the front, answer on the back.
 *
 * <p>A flashcard is a question <em>type</em> here, not a parallel system. It lives in the same
 * table, is served by the same session engine, and is logged in the same attempt log as
 * everything else. That unification is why the Again/Hard/Good/Easy grades feed the very
 * scheduler that drives exam readiness, instead of needing an integration between two
 * separate features.
 *
 * @param front    the prompt
 * @param back     the answer
 * @param mnemonic optional memory aid shown after the reveal
 */
public record FlashcardContent(String front, String back, String mnemonic)
        implements QuestionContent {

    private static final int MAX_SIDE = 4000;

    static FlashcardContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);

        String front = reader.requireString("front", MAX_SIDE);
        String back = reader.requireString("back", MAX_SIDE);
        String mnemonic = reader.optionalString("mnemonic", MAX_SIDE);

        reader.throwIfInvalid();
        return new FlashcardContent(front, back, mnemonic);
    }

    @Override
    public QuestionType type() {
        return QuestionType.FLASHCARD;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("front", front);
        payload.put("back", back);
        if (mnemonic != null) {
            payload.put("mnemonic", mnemonic);
        }
        return payload;
    }

    /**
     * Self-graded, so there is nothing to mark against.
     *
     * <p>Anything but "Again" counts as correct for accuracy, while the exact grade is what
     * the scheduler actually cares about: the difference between a struggle and an instant
     * recall is a difference in <em>interval</em>, not in right and wrong. The score mirrors the
     * grade so difficulty estimation still sees the gradation.
     */
    @Override
    public EvaluationResult evaluate(Answer answer) {
        if (!(answer instanceof Answer.Grade grade)) {
            return EvaluationResult.wrong("Expected a recall grade from 1 to 4.");
        }
        return switch (grade.value()) {
            case 1 -> EvaluationResult.wrong("Marked as not recalled.");
            case 2 -> new EvaluationResult(true, 0.6, "Recalled with difficulty.");
            case 3 -> new EvaluationResult(true, 0.85, null);
            default -> EvaluationResult.right();
        };
    }

    @Override
    public String fingerprint() {
        return back;
    }
}
