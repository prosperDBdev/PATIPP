package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.Map;

/**
 * The type-specific body of a question: its options, accepted answers, front and back.
 *
 * <p>Stored as {@code jsonb} so a new format needs no migration, but never <em>handled</em> as a
 * raw map. Everything crossing into the application is parsed through {@link #parse} first,
 * so by the time any other code touches it the shape is guaranteed.
 *
 * <p>Sealed on purpose. Preparation types use a runtime strategy registry because new ones
 * arrive as data and must work without a deploy; question formats are the opposite, a closed
 * set known at compile time. Sealing gives exhaustive switches, so adding a format and
 * forgetting to evaluate it is a compile error rather than a surprise during an exam.
 *
 * <p>Pure domain code: no Spring, no JPA, no web types. Every rule here is testable in
 * milliseconds without a container.
 */
public sealed interface QuestionContent
        permits McqContent, MultiSelectContent, TrueFalseContent, ShortAnswerContent, FlashcardContent {

    QuestionType type();

    /** The jsonb-shaped form written to {@code question_versions.payload}. */
    Map<String, Object> toPayload();

    /**
     * What is safe to send to a learner who has not answered yet.
     *
     * <p>Separate from {@link #toPayload()} because the payload contains the answer key.
     * Sending the whole thing and hiding it in the interface would put the answers in the
     * browser, where anyone curious enough to open developer tools can read them - and the
     * person most likely to do that is the one whose own study results it corrupts.
     *
     * <p>Each format decides for itself, which is the point of the method living here: a new
     * format cannot be added without answering the question "what may they see first?"
     */
    Map<String, Object> presentation();

    /**
     * The answer key, for after the answer is in. Shown in immediate feedback for practice,
     * and in the summary for modes that defer it.
     */
    Map<String, Object> correctAnswer();

    /**
     * Grades one submission.
     *
     * <p>An answer of the wrong shape for this format scores zero rather than throwing: it
     * means a client sent something incoherent, which is a bad request, not a server fault,
     * and the caller validates shape before reaching here.
     */
    EvaluationResult evaluate(Answer answer);

    /**
     * The text used to detect duplicates on import, over and above the stem. Two questions
     * with identical wording but different options are genuinely different questions.
     */
    String fingerprint();

    /**
     * Parses and validates a raw payload for the given format.
     *
     * @throws ContentValidationException listing every problem found, not just the first
     * @throws IllegalArgumentException   if the format is not implemented in this phase
     */
    static QuestionContent parse(QuestionType type, Map<String, Object> payload) {
        if (!type.isImplemented()) {
            throw new IllegalArgumentException(
                    "Question type " + type + " is declared but not implemented yet.");
        }
        return switch (type) {
            case MCQ -> McqContent.from(payload);
            case MULTI_SELECT -> MultiSelectContent.from(payload);
            case TRUE_FALSE -> TrueFalseContent.from(payload);
            case SHORT_ANSWER -> ShortAnswerContent.from(payload);
            case FLASHCARD -> FlashcardContent.from(payload);
            // Exhaustive by construction: adding a value to QuestionType without handling
            // it here fails to compile, which is the whole reason this hierarchy is sealed.
            case LONG_ANSWER, CODING, DEBUGGING, OUTPUT_PREDICTION, SCENARIO, BEHAVIORAL ->
                    throw new IllegalArgumentException(
                            "Question type " + type + " arrives in a later phase.");
        };
    }
}
