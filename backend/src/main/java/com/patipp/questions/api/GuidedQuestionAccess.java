package com.patipp.questions.api;

import com.patipp.questions.internal.QuestionService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Publishes question authoring without exposing the module's internal service. */
@Component
public class GuidedQuestionAccess {
    private final QuestionService questions;
    private final com.patipp.questions.domain.QuestionRepository repository;

    public GuidedQuestionAccess(QuestionService questions, com.patipp.questions.domain.QuestionRepository repository) {
        this.questions = questions;
        this.repository = repository;
    }

    public QuestionDtos.QuestionResponse create(UUID spaceId, QuestionDtos.CreateQuestionRequest request) {
        return questions.create(spaceId, request);
    }

    public java.util.Map<Integer, UUID> days(UUID spaceId, String key) {
        java.util.Map<Integer, UUID> result = new java.util.LinkedHashMap<>();
        for (var question : repository.findAllLiveInSpace(spaceId)) {
            if (!question.tags().contains(key)) continue;
            for (String tag : question.tags()) {
                if (tag.matches("day-[0-9]{2}")) result.put(Integer.parseInt(tag.substring(4)), question.id());
            }
        }
        return result;
    }
}
