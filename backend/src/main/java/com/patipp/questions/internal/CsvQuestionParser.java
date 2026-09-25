package com.patipp.questions.internal;

import com.patipp.curriculum.api.CurriculumLookup;
import com.patipp.questions.api.QuestionDtos.FieldProblem;
import com.patipp.questions.domain.Difficulty;
import com.patipp.questions.domain.QuestionType;
import com.patipp.questions.domain.content.ContentValidationException;
import com.patipp.questions.domain.content.QuestionContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reads a spreadsheet export into questions.
 *
 * <p>CSV is the format people actually have their material in, so this is forgiving about
 * the things that do not matter (column order, header casing, spacing) and strict about the
 * things that do (the answer, and whether it is coherent).
 *
 * <p>Recognised columns, all case-insensitive:
 * <pre>
 *   type          MCQ | MULTI_SELECT | TRUE_FALSE | SHORT_ANSWER | FLASHCARD   (required)
 *   stem          the question text, or the front of a flashcard              (required)
 *   difficulty    EASY | MEDIUM | HARD | EXPERT                     (default MEDIUM)
 *   subject       subject name; falls back to the import default
 *   topic         topic name within that subject
 *   explanation   shown after answering
 *   tags          separated by | or ;
 *   estimatedSeconds
 *
 *   option1..option8   choices, for MCQ and MULTI_SELECT
 *   correct            which are right: 1-based positions or letters, comma separated
 *   answer             TRUE or FALSE, for TRUE_FALSE
 *   acceptedAnswers    separated by |, for SHORT_ANSWER
 *   matchMode          EXACT | NORMALIZED | KEYWORDS
 *   requiredKeywords   separated by |
 *   back               the reverse of a flashcard
 * </pre>
 */
@Component
public class CsvQuestionParser {

    private static final int MAX_OPTIONS = 8;

    private final CurriculumLookup curriculum;

    public CsvQuestionParser(CurriculumLookup curriculum) {
        this.curriculum = curriculum;
    }

    public List<ParsedQuestion> parse(String csv, UUID spaceId, UUID defaultSubjectId,
                                      UUID defaultTopicId) {
        List<List<String>> rows = CsvReader.parse(csv);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<String> header = rows.getFirst().stream()
                .map(cell -> cell.strip().toLowerCase(Locale.ROOT))
                .toList();

        List<ParsedQuestion> parsed = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            // +1 again so the reported line matches what a spreadsheet shows, where the
            // header is row 1.
            parsed.add(parseRow(toMap(header, rows.get(r)), r + 1, spaceId,
                    defaultSubjectId, defaultTopicId));
        }
        return parsed;
    }

    private Map<String, String> toMap(List<String> header, List<String> cells) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
        }
        return row;
    }

    private ParsedQuestion parseRow(Map<String, String> row, int line, UUID spaceId,
                                    UUID defaultSubjectId, UUID defaultTopicId) {
        List<FieldProblem> problems = new ArrayList<>();

        String stem = value(row, "stem");
        if (stem.isBlank()) {
            problems.add(new FieldProblem("stem", "is required"));
        }

        QuestionType type = null;
        String rawType = value(row, "type");
        if (rawType.isBlank()) {
            problems.add(new FieldProblem("type", "is required"));
        } else {
            try {
                type = QuestionType.valueOf(rawType.toUpperCase(Locale.ROOT).replace(' ', '_'));
                if (!type.isImplemented()) {
                    problems.add(new FieldProblem("type", rawType + " is not supported yet"));
                    type = null;
                }
            } catch (IllegalArgumentException unknown) {
                problems.add(new FieldProblem("type", "unknown question type \"" + rawType + "\""));
            }
        }

        Difficulty difficulty = Difficulty.MEDIUM;
        String rawDifficulty = value(row, "difficulty");
        if (!rawDifficulty.isBlank()) {
            try {
                difficulty = Difficulty.valueOf(rawDifficulty.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                problems.add(new FieldProblem("difficulty",
                        "must be EASY, MEDIUM, HARD or EXPERT"));
            }
        }

        // Subject and topic are named in the file; resolve them to ids, falling back to the
        // defaults chosen in the import dialog.
        UUID subjectId = defaultSubjectId;
        String subjectName = value(row, "subject");
        if (!subjectName.isBlank()) {
            subjectId = curriculum.subjectIdByName(spaceId, subjectName).orElse(null);
            if (subjectId == null) {
                problems.add(new FieldProblem("subject",
                        "no subject called \"" + subjectName + "\" in this space"));
            }
        }

        UUID topicId = defaultTopicId;
        String topicName = value(row, "topic");
        if (!topicName.isBlank() && subjectId != null) {
            topicId = curriculum.topicIdByName(spaceId, subjectId, topicName).orElse(null);
            if (topicId == null) {
                problems.add(new FieldProblem("topic",
                        "no topic called \"" + topicName + "\" under that subject"));
            }
        }

        if (subjectId == null && problems.stream().noneMatch(p -> p.field().equals("subject"))) {
            problems.add(new FieldProblem("subject",
                    "is required; name it in the file or choose a default"));
        }

        QuestionContent content = null;
        if (type != null) {
            try {
                content = QuestionContent.parse(type, buildPayload(type, row, problems));
            } catch (ContentValidationException invalid) {
                invalid.errors().forEach(error ->
                        problems.add(new FieldProblem(error.field(), error.message())));
            } catch (IllegalArgumentException unsupported) {
                problems.add(new FieldProblem("type", unsupported.getMessage()));
            }
        }

        if (!problems.isEmpty()) {
            return ParsedQuestion.invalid(line, stem, rawType, problems);
        }

        return new ParsedQuestion(line, stem, emptyToNull(value(row, "explanation")), List.of(),
                type, difficulty, content, subjectId, topicId,
                parseInt(value(row, "estimatedseconds")),
                splitList(value(row, "tags")), List.of());
    }

    /** Turns the flat spreadsheet columns into the nested payload each format expects. */
    private Map<String, Object> buildPayload(QuestionType type, Map<String, String> row,
                                             List<FieldProblem> problems) {
        Map<String, Object> payload = new LinkedHashMap<>();

        switch (type) {
            case MCQ, MULTI_SELECT -> {
                List<String> texts = new ArrayList<>();
                for (int i = 1; i <= MAX_OPTIONS; i++) {
                    String option = value(row, "option" + i);
                    if (!option.isBlank()) {
                        texts.add(option);
                    }
                }
                List<Integer> correct = parseCorrectPositions(value(row, "correct"), texts.size(),
                        problems);

                List<Map<String, Object>> options = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    options.add(Map.of(
                            "id", String.valueOf((char) ('a' + i)),
                            "text", texts.get(i),
                            "correct", correct.contains(i + 1)));
                }
                payload.put("options", options);
            }
            case TRUE_FALSE -> {
                String answer = value(row, "answer");
                if (answer.isBlank()) {
                    problems.add(new FieldProblem("answer", "is required for TRUE_FALSE"));
                } else {
                    payload.put("answer", answer);
                }
            }
            case SHORT_ANSWER -> {
                payload.put("acceptedAnswers", splitList(value(row, "acceptedanswers")));
                String mode = value(row, "matchmode");
                if (!mode.isBlank()) {
                    payload.put("matchMode", mode);
                }
                List<String> keywords = splitList(value(row, "requiredkeywords"));
                if (!keywords.isEmpty()) {
                    payload.put("requiredKeywords", keywords);
                }
            }
            case FLASHCARD -> {
                // The stem doubles as the front, so a flashcard row needs only one extra column.
                payload.put("front", value(row, "stem"));
                payload.put("back", value(row, "back"));
            }
            case OUTPUT_PREDICTION -> {
                payload.put("code", rawValue(row, "code"));
                payload.put("expectedOutput", rawValue(row, "expectedoutput"));
                putIfPresent(payload, "language", value(row, "language"));
                putIfPresent(payload, "matchMode", value(row, "matchmode"));
            }
            case DEBUGGING -> {
                payload.put("code", rawValue(row, "code"));
                payload.put("defectSummary", value(row, "defectsummary"));
                putIfPresent(payload, "language", value(row, "language"));
                putIfPresent(payload, "fix", rawValue(row, "fix"));

                String line = value(row, "defectline");
                if (line.isBlank()) {
                    problems.add(new FieldProblem("defectLine", "is required for DEBUGGING"));
                } else {
                    payload.put("defectLine", line);
                }
                payload.put("rubric", splitList(value(row, "rubric")));
            }
            case CODING -> {
                payload.put("referenceSolution", rawValue(row, "referencesolution"));
                putIfPresent(payload, "starterCode", rawValue(row, "startercode"));
                putIfPresent(payload, "language", value(row, "language"));
                putIfPresent(payload, "complexity", value(row, "complexity"));
                payload.put("rubric", splitList(value(row, "rubric")));
            }
            default -> problems.add(new FieldProblem("type", type + " cannot be imported yet"));
        }

        return payload;
    }

    /**
     * Reads the {@code correct} column, accepting either 1-based positions ("2" or "1,3") or
     * letters ("b", "a,c"), because both are what people naturally type.
     */
    private List<Integer> parseCorrectPositions(String raw, int optionCount,
                                                List<FieldProblem> problems) {
        List<Integer> positions = new ArrayList<>();
        if (raw.isBlank()) {
            problems.add(new FieldProblem("correct", "is required; give the option number or letter"));
            return positions;
        }

        for (String token : raw.split("[,;|]")) {
            String value = token.strip();
            if (value.isEmpty()) {
                continue;
            }
            int position;
            if (value.length() == 1 && Character.isLetter(value.charAt(0))) {
                position = Character.toLowerCase(value.charAt(0)) - 'a' + 1;
            } else {
                try {
                    position = Integer.parseInt(value);
                } catch (NumberFormatException notANumber) {
                    problems.add(new FieldProblem("correct",
                            "\"" + value + "\" is neither an option number nor a letter"));
                    continue;
                }
            }
            if (position < 1 || position > optionCount) {
                problems.add(new FieldProblem("correct",
                        "option " + value + " does not exist; there are " + optionCount));
                continue;
            }
            positions.add(position);
        }
        return positions;
    }

    private static String value(Map<String, String> row, String key) {
        String raw = row.get(key);
        return raw == null ? "" : raw.strip();
    }

    /**
     * A cell kept exactly as written.
     *
     * <p>For code and expected output, where indentation and trailing blank lines are the
     * content rather than noise around it. Stripping a snippet quietly re-indents its first
     * line, which is how an imported question comes out subtly wrong.
     */
    private static String rawValue(Map<String, String> row, String key) {
        String raw = row.get(key);
        return raw == null ? "" : raw;
    }

    /** Adds a value only when the author supplied one, so absent stays absent. */
    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private static String emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }

    private static Integer parseInt(String value) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Splits on pipe or semicolon. Comma is not used: it collides with CSV itself. */
    private static List<String> splitList(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[|;]"))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
