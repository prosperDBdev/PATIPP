package com.patipp.questions.internal;

import com.patipp.questions.api.QuestionDtos.FieldProblem;
import com.patipp.questions.domain.Difficulty;
import com.patipp.questions.domain.QuestionType;
import com.patipp.questions.domain.content.QuestionContent;
import java.util.List;
import java.util.UUID;

/**
 * One record read from an import file: either fully understood, or a list of what is wrong
 * with it.
 *
 * <p>Parsing produces this rather than throwing, so a fifty-row file yields fifty verdicts in
 * one pass. Reporting the first failure and stopping would turn fixing a spreadsheet into an
 * afternoon of round trips.
 *
 * @param line     1-based position in the source file, so a problem can be traced back to
 *                 the row that caused it
 * @param problems empty when the record is usable
 */
public record ParsedQuestion(
        int line,
        String stem,
        String explanation,
        List<String> hints,
        QuestionType type,
        Difficulty difficulty,
        QuestionContent content,
        UUID subjectId,
        UUID topicId,
        Integer estimatedSeconds,
        List<String> tags,
        List<FieldProblem> problems) {

    public boolean isValid() {
        return problems.isEmpty();
    }

    public static ParsedQuestion invalid(int line, String stem, String type,
                                         List<FieldProblem> problems) {
        return new ParsedQuestion(line, stem, null, List.of(), null, null, null,
                null, null, null, List.of(), List.copyOf(problems));
    }

    /** The type name for reporting, tolerating a record that failed before the type parsed. */
    public String typeLabel() {
        return type == null ? "?" : type.name();
    }
}
