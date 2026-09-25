package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A problem you solve by hand, then grade yourself against the author's rubric.
 *
 * <p>Nothing here executes code, and that is a decision rather than a shortcoming. The gap
 * this format closes is not "the platform cannot run my solution" - it is that writing
 * solutions in an editor happens <em>outside</em> the platform, so it never reaches the
 * attempt log and never moves the ability estimate, the review schedule or the readiness
 * score. A readiness figure computed only over multiple-choice questions is reporting
 * confidently on the half of your preparation that matters least for a live technical
 * interview.
 *
 * <p>So: the problem is shown, you solve it in your own editor narrating as you go, and then
 * the reference solution and rubric appear and you grade yourself Again/Hard/Good/Easy. The
 * attempt that results is indistinguishable downstream from any other - same Elo, same
 * scheduling, same analytics - except that it is stamped {@code SELF} so the mix can always
 * be audited.
 */
public record CodingContent(
        String language,
        String starterCode,
        String referenceSolution,
        String complexity,
        List<String> rubric) implements QuestionContent {

    private static final int MAX_CODE = 12000;
    private static final int MAX_RUBRIC_ITEMS = 10;
    private static final int MAX_RUBRIC_LENGTH = 300;

    /** Below this self-grade the attempt is not counted as solved. */
    static final int PASSING_GRADE = 3;

    public CodingContent {
        rubric = List.copyOf(rubric);
    }

    static CodingContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);

        String reference = reader.requireVerbatim("referenceSolution", MAX_CODE);
        String starter = reader.optionalVerbatim("starterCode", MAX_CODE);
        String language = reader.optionalString("language", 40);
        String complexity = reader.optionalString("complexity", 80);
        List<String> rubric = reader.stringList("rubric", MAX_RUBRIC_ITEMS, MAX_RUBRIC_LENGTH);

        if (rubric.isEmpty()) {
            // The rubric is the whole mechanism. Without it "grade yourself" means "give
            // yourself a mark", which is not evidence of anything.
            reader.reject("rubric",
                    "must list at least one thing a good solution does, since this format is "
                            + "graded against it");
        }

        reader.throwIfInvalid();
        return new CodingContent(
                language == null ? "text" : language.strip(),
                starter, reference, complexity, rubric);
    }

    @Override
    public QuestionType type() {
        return QuestionType.CODING;
    }

    @Override
    public EvaluationMode evaluation() {
        return EvaluationMode.SELF;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", language);
        if (starterCode != null) {
            payload.put("starterCode", starterCode);
        }
        payload.put("referenceSolution", referenceSolution);
        if (complexity != null) {
            payload.put("complexity", complexity);
        }
        payload.put("rubric", rubric);
        return payload;
    }

    /**
     * The language and any starter code. Not the solution, and not the rubric.
     *
     * <p>Withholding the rubric until afterwards is the point of it: read first, it is a list
     * of hints; read after, it is the standard you are judging your own work against.
     */
    @Override
    public Map<String, Object> presentation() {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("language", language);
        if (starterCode != null) {
            shown.put("starterCode", starterCode);
        }
        shown.put("selfGraded", true);
        shown.put("rubricItemCount", rubric.size());
        return shown;
    }

    @Override
    public Map<String, Object> correctAnswer() {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("referenceSolution", referenceSolution);
        key.put("rubric", rubric);
        if (complexity != null) {
            key.put("complexity", complexity);
        }
        return key;
    }

    /**
     * The learner's own verdict, on the same 1-4 scale a flashcard uses.
     *
     * <p>The same scale on purpose: it is already what the spaced-repetition scheduler
     * consumes, so a coding problem gets scheduled for revision by exactly the mechanism that
     * schedules everything else, with no special case anywhere.
     */
    @Override
    public EvaluationResult evaluate(Answer answer) {
        if (!(answer instanceof Answer.Grade grade)) {
            return EvaluationResult.wrong("Expected a self-assessment from 1 to 4.");
        }

        double score = switch (grade.value()) {
            case 1 -> 0.0;
            case 2 -> 0.4;
            case 3 -> 0.75;
            default -> 1.0;
        };

        if (grade.value() >= PASSING_GRADE) {
            return new EvaluationResult(true, score, null);
        }
        return new EvaluationResult(false, score, grade.value() == 1
                ? "Recorded as not solved. It will come back sooner."
                : "Recorded as solved with difficulty.");
    }

    @Override
    public String fingerprint() {
        return referenceSolution.strip();
    }
}
