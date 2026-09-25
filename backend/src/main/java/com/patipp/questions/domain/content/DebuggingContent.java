package com.patipp.questions.domain.content;

import com.patipp.questions.domain.QuestionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A snippet with a defect in it: find the line, then say why.
 *
 * <p>Two halves, graded two ways. Which line you pointed at is a fact the platform can check.
 * Whether your explanation of the defect was any good is not, so you grade that yourself
 * against the author's account of it, revealed afterwards.
 *
 * <p>Splitting it this way is what makes the question honest rather than either half alone.
 * Marking only the line number rewards a lucky guess in a five-line function; asking only for
 * an explanation and trusting the self-grade gives no anchor at all.
 */
public record DebuggingContent(
        String language,
        String code,
        int defectLine,
        String defectSummary,
        String fix,
        List<String> rubric) implements QuestionContent {

    private static final int MAX_CODE = 8000;
    private static final int MAX_SUMMARY = 2000;
    private static final int MAX_RUBRIC_ITEMS = 8;
    private static final int MAX_RUBRIC_LENGTH = 300;

    /** Pointing at the right line is worth this much; explaining it is worth the rest. */
    static final double LINE_WEIGHT = 0.5;

    /** The self-grade at or above which an explanation counts as sound. */
    static final int PASSING_GRADE = 3;

    public DebuggingContent {
        rubric = List.copyOf(rubric);
    }

    static DebuggingContent from(Map<String, Object> payload) {
        PayloadReader reader = new PayloadReader(payload);

        String code = reader.requireVerbatim("code", MAX_CODE);
        String summary = reader.requireVerbatim("defectSummary", MAX_SUMMARY);
        String fix = reader.optionalVerbatim("fix", MAX_CODE);
        String language = reader.optionalString("language", 40);
        List<String> rubric = reader.stringList("rubric", MAX_RUBRIC_ITEMS, MAX_RUBRIC_LENGTH);

        int lines = code.isEmpty() ? 1 : code.split("\n", -1).length;
        Integer line = reader.optionalInt("defectLine", 1, lines);
        if (line == null) {
            // Required, but reported against the real bound rather than a generic message, so
            // an author who typed line 40 of a 12-line snippet is told exactly that.
            reader.reject("defectLine",
                    "is required and must be between 1 and " + lines);
            line = 1;
        }

        reader.throwIfInvalid();
        return new DebuggingContent(
                language == null ? "text" : language.strip(), code, line, summary, fix, rubric);
    }

    @Override
    public QuestionType type() {
        return QuestionType.DEBUGGING;
    }

    @Override
    public EvaluationMode evaluation() {
        return EvaluationMode.MIXED;
    }

    @Override
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("language", language);
        payload.put("code", code);
        payload.put("defectLine", defectLine);
        payload.put("defectSummary", defectSummary);
        if (fix != null) {
            payload.put("fix", fix);
        }
        payload.put("rubric", rubric);
        return payload;
    }

    /**
     * The code and how many lines it has. Not which line is wrong, and not the rubric.
     *
     * <p>The rubric is withheld until after the answer on purpose: read beforehand, it is a
     * checklist that leads you straight to the defect, and the skill being practised is
     * finding it unaided.
     */
    @Override
    public Map<String, Object> presentation() {
        return Map.of(
                "language", language,
                "code", code,
                "lineCount", code.split("\n", -1).length,
                "selfGraded", true);
    }

    @Override
    public Map<String, Object> correctAnswer() {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("defectLine", defectLine);
        key.put("defectSummary", defectSummary);
        if (fix != null) {
            key.put("fix", fix);
        }
        key.put("rubric", rubric);
        return key;
    }

    /**
     * Half for the line, half for the learner's own verdict on their explanation.
     *
     * <p>Counts as correct only when both hold. Finding the right line and admitting you could
     * not say why is a genuinely partial answer, and recording it as a win would teach the
     * adaptive engine that you understand something you do not.
     */
    @Override
    public EvaluationResult evaluate(Answer answer) {
        if (!(answer instanceof Answer.Diagnosis diagnosis)) {
            return EvaluationResult.wrong("Expected a line number and a self-assessment.");
        }

        boolean lineFound = diagnosis.line() == defectLine;
        double explanationScore = switch (diagnosis.grade()) {
            case 1 -> 0.0;
            case 2 -> 0.2;
            case 3 -> 0.35;
            default -> 1.0 - LINE_WEIGHT;
        };

        double score = (lineFound ? LINE_WEIGHT : 0.0) + explanationScore;
        boolean correct = lineFound && diagnosis.grade() >= PASSING_GRADE;

        if (correct) {
            return new EvaluationResult(true, Math.min(1.0, score), null);
        }
        return new EvaluationResult(false, Math.min(1.0, score),
                lineFound
                        ? "Right line. You judged your explanation of it as weak."
                        : "The defect is on line " + defectLine + ".");
    }

    @Override
    public String fingerprint() {
        return code.strip() + "||" + defectLine;
    }
}
