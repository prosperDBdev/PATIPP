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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the native JSON interchange format.
 *
 * <p>This is the same shape that export produces, so a bank can be taken out of one space and
 * put into another without a translation step. Where CSV flattens everything into columns,
 * JSON carries the payload as-is, which makes it the format for anything with structure.
 *
 * <p>Accepts either a bare array or an object with a {@code questions} array, because both
 * are what people end up with when they assemble a file by hand.
 */
@Component
public class JsonQuestionParser {

    private final ObjectMapper objectMapper;
    private final CurriculumLookup curriculum;

    public JsonQuestionParser(ObjectMapper objectMapper, CurriculumLookup curriculum) {
        this.objectMapper = objectMapper;
        this.curriculum = curriculum;
    }

    /**
     * @return one entry per element, or a single invalid entry when the document itself is
     *         not readable. A malformed file is a problem with the file, not an exception.
     */
    public List<ParsedQuestion> parse(String json, UUID spaceId, UUID defaultSubjectId,
                                      UUID defaultTopicId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException malformed) {
            return List.of(ParsedQuestion.invalid(1, "", null,
                    List.of(new FieldProblem("file", "is not valid JSON"))));
        }

        JsonNode array = root.isArray() ? root : root.path("questions");
        if (!array.isArray()) {
            return List.of(ParsedQuestion.invalid(1, "", null, List.of(new FieldProblem("file",
                    "must be an array of questions, or an object with a \"questions\" array"))));
        }

        List<ParsedQuestion> parsed = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            parsed.add(parseOne(array.get(i), i + 1, spaceId, defaultSubjectId, defaultTopicId));
        }
        return parsed;
    }

    private ParsedQuestion parseOne(JsonNode node, int index, UUID spaceId,
                                    UUID defaultSubjectId, UUID defaultTopicId) {
        List<FieldProblem> problems = new ArrayList<>();

        String stem = text(node, "stem");
        if (stem.isBlank()) {
            problems.add(new FieldProblem("stem", "is required"));
        }

        QuestionType type = null;
        String rawType = text(node, "type");
        if (rawType.isBlank()) {
            problems.add(new FieldProblem("type", "is required"));
        } else {
            try {
                type = QuestionType.valueOf(rawType.toUpperCase(Locale.ROOT));
                if (!type.isImplemented()) {
                    problems.add(new FieldProblem("type", rawType + " is not supported yet"));
                    type = null;
                }
            } catch (IllegalArgumentException unknown) {
                problems.add(new FieldProblem("type", "unknown question type \"" + rawType + "\""));
            }
        }

        Difficulty difficulty = Difficulty.MEDIUM;
        String rawDifficulty = text(node, "difficulty");
        if (!rawDifficulty.isBlank()) {
            try {
                difficulty = Difficulty.valueOf(rawDifficulty.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                problems.add(new FieldProblem("difficulty", "must be EASY, MEDIUM, HARD or EXPERT"));
            }
        }

        UUID subjectId = resolveSubject(node, spaceId, defaultSubjectId, problems);
        UUID topicId = resolveTopic(node, spaceId, subjectId, defaultTopicId, problems);

        QuestionContent content = null;
        if (type != null) {
            JsonNode payloadNode = node.path("payload");
            if (payloadNode.isMissingNode() || !payloadNode.isObject()) {
                problems.add(new FieldProblem("payload", "is required and must be an object"));
            } else {
                try {
                    content = QuestionContent.parse(type, toMap(payloadNode));
                } catch (ContentValidationException invalid) {
                    invalid.errors().forEach(error ->
                            problems.add(new FieldProblem(error.field(), error.message())));
                } catch (IllegalArgumentException unsupported) {
                    problems.add(new FieldProblem("type", unsupported.getMessage()));
                }
            }
        }

        if (!problems.isEmpty()) {
            return ParsedQuestion.invalid(index, stem, rawType, problems);
        }

        return new ParsedQuestion(index, stem, emptyToNull(text(node, "explanation")),
                stringList(node, "hints"), type, difficulty, content, subjectId, topicId,
                node.hasNonNull("estimatedSeconds") ? node.get("estimatedSeconds").asInt() : null,
                stringList(node, "tags"), List.of());
    }

    private UUID resolveSubject(JsonNode node, UUID spaceId, UUID fallback,
                                List<FieldProblem> problems) {
        String name = text(node, "subject");
        if (!name.isBlank()) {
            UUID resolved = curriculum.subjectIdByName(spaceId, name).orElse(null);
            if (resolved == null) {
                problems.add(new FieldProblem("subject",
                        "no subject called \"" + name + "\" in this space"));
            }
            return resolved;
        }
        if (fallback == null) {
            problems.add(new FieldProblem("subject",
                    "is required; name it in the file or choose a default"));
        }
        return fallback;
    }

    private UUID resolveTopic(JsonNode node, UUID spaceId, UUID subjectId, UUID fallback,
                              List<FieldProblem> problems) {
        String name = text(node, "topic");
        if (name.isBlank() || subjectId == null) {
            return fallback;
        }
        UUID resolved = curriculum.topicIdByName(spaceId, subjectId, name).orElse(null);
        if (resolved == null) {
            problems.add(new FieldProblem("topic",
                    "no topic called \"" + name + "\" under that subject"));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = objectMapper.convertValue(node, LinkedHashMap.class);
        return map == null ? Map.of() : map;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asString().strip();
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(item -> {
            String value = item.asString().strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        });
        return values;
    }

    private static String emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
