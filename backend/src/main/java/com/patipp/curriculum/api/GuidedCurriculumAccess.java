package com.patipp.curriculum.api;

import com.patipp.curriculum.internal.CurriculumService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reuses the curriculum's authoring rules when installing an authored guide. */
@Component
public class GuidedCurriculumAccess {
    private final CurriculumService curriculum;

    public GuidedCurriculumAccess(CurriculumService curriculum) {
        this.curriculum = curriculum;
    }

    public UUID topic(UUID spaceId, String subject, String topic) {
        var parent = curriculum.curriculum(spaceId).stream()
                .filter(row -> row.name().equals(subject)).findFirst()
                .orElseGet(() -> curriculum.createSubject(spaceId,
                        new CurriculumDtos.CreateSubjectRequest(subject, null, null, null)));
        return parent.topics().stream().filter(row -> row.name().equals(topic))
                .map(CurriculumDtos.TopicResponse::id).findFirst()
                .orElseGet(() -> curriculum.createTopic(spaceId,
                        new CurriculumDtos.CreateTopicRequest(parent.id(), null, topic, null, null)).id());
    }

    public UUID subject(UUID spaceId, String name) {
        return curriculum.curriculum(spaceId).stream().filter(row -> row.name().equals(name))
                .findFirst().orElseThrow().id();
    }
}
