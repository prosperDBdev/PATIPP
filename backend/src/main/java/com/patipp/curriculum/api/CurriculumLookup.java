package com.patipp.curriculum.api;

import com.patipp.curriculum.domain.SubjectRepository;
import com.patipp.curriculum.domain.TopicRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The curriculum module's published lookup, for other modules that need to check a subject
 * or topic before pointing at it.
 *
 * <p>Deliberately narrow and read-only. {@code questions} needs to answer "is this a real
 * subject in this space?" and nothing more; handing it the repositories, or letting it reach
 * into {@code curriculum.internal}, would let it grow a dependency on how the curriculum
 * happens to be stored. An ArchUnit rule stops that, and this is the sanctioned way through.
 *
 * <p>The database already refuses a cross-space reference through a composite foreign key,
 * so this is not the safety net. It exists to turn what would surface as an opaque
 * constraint violation into a precise, actionable 404.
 */
@Component
public class CurriculumLookup {

    private final SubjectRepository subjects;
    private final TopicRepository topics;

    public CurriculumLookup(SubjectRepository subjects, TopicRepository topics) {
        this.subjects = subjects;
        this.topics = topics;
    }

    @Transactional(readOnly = true)
    public boolean subjectExists(UUID spaceId, UUID subjectId) {
        return subjectId != null
                && subjects.findInSpace(subjectId, spaceId)
                        .filter(subject -> !subject.isArchived())
                        .isPresent();
    }

    @Transactional(readOnly = true)
    public boolean topicExists(UUID spaceId, UUID topicId) {
        return topicId != null
                && topics.findInSpace(topicId, spaceId)
                        .filter(topic -> !topic.isArchived())
                        .isPresent();
    }

    /** The subject a topic belongs to, so a caller can keep the two consistent. */
    @Transactional(readOnly = true)
    public Optional<UUID> subjectOfTopic(UUID spaceId, UUID topicId) {
        if (topicId == null) {
            return Optional.empty();
        }
        return topics.findInSpace(topicId, spaceId)
                .filter(topic -> !topic.isArchived())
                .map(topic -> topic.subjectId());
    }

    /** Resolves a subject by name, case-insensitively. Used by import, where files name subjects. */
    @Transactional(readOnly = true)
    public Optional<UUID> subjectIdByName(UUID spaceId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return subjects.findLiveInSpace(spaceId).stream()
                .filter(subject -> subject.name().equalsIgnoreCase(name.strip()))
                .map(subject -> subject.id())
                .findFirst();
    }

    /**
     * Subject id to name for a whole space, for callers that render or export many at once.
     *
     * <p>Export writes subjects by name rather than id, because an id means nothing in the
     * space you import into while a name is what a person actually wrote.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> subjectNames(UUID spaceId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        subjects.findLiveInSpace(spaceId).forEach(subject -> names.put(subject.id(), subject.name()));
        return names;
    }

    /** Topic id to name for a whole space. */
    @Transactional(readOnly = true)
    public Map<UUID, String> topicNames(UUID spaceId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        topics.findLiveInSpace(spaceId).forEach(topic -> names.put(topic.id(), topic.name()));
        return names;
    }

    /** Resolves a topic by name within a subject, case-insensitively. */
    @Transactional(readOnly = true)
    public Optional<UUID> topicIdByName(UUID spaceId, UUID subjectId, String name) {
        if (subjectId == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        return topics.findLiveInSubject(spaceId, subjectId).stream()
                .filter(topic -> topic.name().equalsIgnoreCase(name.strip()))
                .map(topic -> topic.id())
                .findFirst();
    }
}
