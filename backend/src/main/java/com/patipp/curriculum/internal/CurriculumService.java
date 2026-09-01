package com.patipp.curriculum.internal;

import com.patipp.common.error.BadRequestException;
import com.patipp.common.error.ConflictException;
import com.patipp.common.error.NotFoundException;
import com.patipp.curriculum.api.CurriculumDtos.CreateSubjectRequest;
import com.patipp.curriculum.api.CurriculumDtos.CreateTopicRequest;
import com.patipp.curriculum.api.CurriculumDtos.SubjectResponse;
import com.patipp.curriculum.api.CurriculumDtos.TopicResponse;
import com.patipp.curriculum.api.CurriculumDtos.UpdateSubjectRequest;
import com.patipp.curriculum.api.CurriculumDtos.UpdateTopicRequest;
import com.patipp.curriculum.domain.Subject;
import com.patipp.curriculum.domain.SubjectRepository;
import com.patipp.curriculum.domain.Topic;
import com.patipp.curriculum.domain.TopicRepository;
import com.patipp.preparations.api.SpaceAccessGuard;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subjects and the topic tree for one preparation space.
 *
 * <p>Every public method begins by resolving the space through {@link SpaceAccessGuard}, so
 * ownership is established before any other query runs. No method here accepts a user id:
 * the caller cannot influence whose curriculum is read.
 */
@Service
public class CurriculumService {

    private final SubjectRepository subjects;
    private final TopicRepository topics;
    private final SpaceAccessGuard accessGuard;
    private final Clock clock;

    public CurriculumService(SubjectRepository subjects, TopicRepository topics,
                             SpaceAccessGuard accessGuard, Clock clock) {
        this.subjects = subjects;
        this.topics = topics;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- curriculum

    /**
     * The whole curriculum in one call: subjects, each with its topics already assembled
     * into a tree. The editor needs all of it at once, and two round trips plus client-side
     * assembly would be a worse version of the same thing.
     */
    @Transactional(readOnly = true)
    public List<SubjectResponse> curriculum(UUID spaceId) {
        accessGuard.requireOwned(spaceId);

        List<Topic> allTopics = topics.findLiveInSpace(spaceId);
        Map<UUID, List<TopicResponse>> treesBySubject = buildTreesBySubject(allTopics);

        return subjects.findLiveInSpace(spaceId).stream()
                .map(subject -> toSubjectResponse(subject,
                        treesBySubject.getOrDefault(subject.id(), List.of())))
                .toList();
    }

    // ---------------------------------------------------------------- subjects

    @Transactional
    public SubjectResponse createSubject(UUID spaceId, CreateSubjectRequest request) {
        accessGuard.requireWritable(spaceId);

        String name = request.name().strip();
        if (subjects.existsLiveWithName(spaceId, name)) {
            throw new ConflictException("subject.name_taken",
                    "This space already has a subject called \"" + name + "\".");
        }

        short position = (short) (subjects.maxPosition(spaceId) + 1);
        Subject subject = Subject.create(spaceId, name, request.description(),
                request.color(), position, request.weight());

        return toSubjectResponse(subjects.save(subject), List.of());
    }

    @Transactional
    public SubjectResponse updateSubject(UUID spaceId, UUID subjectId, UpdateSubjectRequest request) {
        accessGuard.requireWritable(spaceId);
        Subject subject = requireSubject(spaceId, subjectId);

        if (request.name() != null) {
            String name = request.name().strip();
            if (subjects.existsOtherLiveWithName(spaceId, name, subjectId)) {
                throw new ConflictException("subject.name_taken",
                        "This space already has a subject called \"" + name + "\".");
            }
            subject.rename(name);
        }
        if (request.description() != null) {
            subject.describe(request.description());
        }
        if (request.color() != null) {
            subject.recolour(request.color());
        }
        if (request.position() != null) {
            subject.reposition(request.position());
        }
        if (request.weight() != null) {
            subject.reweigh(request.weight());
        }

        return toSubjectResponse(subject, buildTree(topics.findLiveInSubject(spaceId, subjectId)));
    }

    /**
     * Archives the subject and, with it, every topic underneath. Cascading here is right
     * because a topic without its subject has no meaning; nothing is hard-deleted, so the
     * questions and attempts that will reference these rows in later phases stay intact.
     */
    @Transactional
    public void archiveSubject(UUID spaceId, UUID subjectId) {
        accessGuard.requireWritable(spaceId);
        Subject subject = requireSubject(spaceId, subjectId);

        var now = clock.instant();
        topics.findLiveInSubject(spaceId, subjectId).forEach(topic -> topic.archive(now));
        subject.archive(now);
    }

    // ---------------------------------------------------------------- topics

    @Transactional
    public TopicResponse createTopic(UUID spaceId, CreateTopicRequest request) {
        accessGuard.requireWritable(spaceId);

        Topic parent = null;
        UUID subjectId = request.subjectId();

        if (request.parentTopicId() != null) {
            parent = requireTopic(spaceId, request.parentTopicId());
            if (parent.depth() >= Topic.MAX_DEPTH) {
                throw new BadRequestException("topic.too_deep",
                        "Topics can nest at most " + (Topic.MAX_DEPTH + 1) + " levels deep.");
            }
            // The subject is taken from the parent, never from the request, so a child can
            // never end up filed under a different subject from its parent.
            subjectId = parent.subjectId();
        } else if (subjectId == null) {
            throw new BadRequestException("topic.subject_required",
                    "A top-level topic must specify a subject.");
        }

        Subject subject = requireSubject(spaceId, subjectId);

        String name = request.name().strip();
        if (topics.existsLiveSiblingWithName(subject.id(),
                parent == null ? null : parent.id(), name)) {
            throw new ConflictException("topic.name_taken",
                    "There is already a topic called \"" + name + "\" at that level.");
        }

        short position = (short) (topics.maxSiblingPosition(
                subject.id(), parent == null ? null : parent.id()) + 1);

        Topic topic = Topic.create(spaceId, subject.id(), parent, name,
                request.description(), position, request.weight());

        return toTopicResponse(topics.save(topic), List.of());
    }

    @Transactional
    public TopicResponse updateTopic(UUID spaceId, UUID topicId, UpdateTopicRequest request) {
        accessGuard.requireWritable(spaceId);
        Topic topic = requireTopic(spaceId, topicId);

        boolean renamed = false;
        if (request.name() != null) {
            String name = request.name().strip();
            if (topics.existsOtherLiveSiblingWithName(
                    topic.subjectId(), topic.parentTopicId(), name, topicId)) {
                throw new ConflictException("topic.name_taken",
                        "There is already a topic called \"" + name + "\" at that level.");
            }
            topic.rename(name);
            renamed = true;
        }
        if (request.description() != null) {
            topic.describe(request.description());
        }
        if (request.position() != null) {
            topic.reposition(request.position());
        }
        if (request.weight() != null) {
            topic.reweigh(request.weight());
        }

        if (renamed) {
            rebuildPathsFrom(spaceId, topic);
        }

        return toTopicResponse(topic, List.of());
    }

    @Transactional
    public void archiveTopic(UUID spaceId, UUID topicId) {
        accessGuard.requireWritable(spaceId);
        Topic topic = requireTopic(spaceId, topicId);

        var now = clock.instant();
        // Breadth-first over descendants: archiving a topic must not orphan its subtopics.
        Deque<Topic> pending = new ArrayDeque<>();
        pending.add(topic);
        while (!pending.isEmpty()) {
            Topic current = pending.removeFirst();
            current.archive(now);
            pending.addAll(topics.findLiveChildren(spaceId, current.id()));
        }
    }

    /**
     * Recomputes the materialised path for a renamed topic and everything beneath it. A
     * stale path is worse than no path: it would show the old name in breadcrumbs and in
     * every later analytics grouping that reads it.
     */
    private void rebuildPathsFrom(UUID spaceId, Topic root) {
        String parentPath = null;
        if (root.parentTopicId() != null) {
            parentPath = requireTopic(spaceId, root.parentTopicId()).path();
        }
        root.rebuildPath(parentPath);

        Deque<Topic> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Topic current = pending.removeFirst();
            for (Topic child : topics.findLiveChildren(spaceId, current.id())) {
                child.rebuildPath(current.path());
                pending.add(child);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private Subject requireSubject(UUID spaceId, UUID subjectId) {
        return subjects.findInSpace(subjectId, spaceId)
                .filter(subject -> !subject.isArchived())
                .orElseThrow(() -> new NotFoundException("subject.not_found", "No such subject."));
    }

    private Topic requireTopic(UUID spaceId, UUID topicId) {
        return topics.findInSpace(topicId, spaceId)
                .filter(topic -> !topic.isArchived())
                .orElseThrow(() -> new NotFoundException("topic.not_found", "No such topic."));
    }

    private Map<UUID, List<TopicResponse>> buildTreesBySubject(List<Topic> allTopics) {
        Map<UUID, List<Topic>> bySubject = new LinkedHashMap<>();
        for (Topic topic : allTopics) {
            bySubject.computeIfAbsent(topic.subjectId(), key -> new ArrayList<>()).add(topic);
        }

        Map<UUID, List<TopicResponse>> result = new LinkedHashMap<>();
        bySubject.forEach((subjectId, subjectTopics) -> result.put(subjectId, buildTree(subjectTopics)));
        return result;
    }

    /**
     * Assembles a flat, depth-ordered list into a tree in one pass. The repository orders by
     * depth, so a parent is always converted before its children and is guaranteed to be in
     * the lookup by the time a child needs it.
     */
    private List<TopicResponse> buildTree(List<Topic> flat) {
        Map<UUID, List<TopicResponse>> childrenOf = new LinkedHashMap<>();
        List<TopicResponse> roots = new ArrayList<>();

        for (Topic topic : flat) {
            List<TopicResponse> children = new ArrayList<>();
            childrenOf.put(topic.id(), children);
            TopicResponse response = toTopicResponse(topic, children);

            if (topic.parentTopicId() == null) {
                roots.add(response);
            } else {
                List<TopicResponse> siblings = childrenOf.get(topic.parentTopicId());
                if (siblings != null) {
                    siblings.add(response);
                } else {
                    // The parent is archived while the child is not. Surfacing the child at
                    // the root is better than silently dropping it: the user can then see it
                    // and deal with it, rather than wondering where their topic went.
                    roots.add(response);
                }
            }
        }

        return roots;
    }

    private SubjectResponse toSubjectResponse(Subject subject, List<TopicResponse> topicTree) {
        return new SubjectResponse(
                subject.id(), subject.name(), subject.description(), subject.color(),
                subject.position(), subject.weight(), subject.createdAt(), topicTree);
    }

    private TopicResponse toTopicResponse(Topic topic, List<TopicResponse> children) {
        return new TopicResponse(
                topic.id(), topic.subjectId(), topic.parentTopicId(), topic.name(),
                topic.description(), topic.position(), topic.weight(),
                topic.depth(), topic.path(), children);
    }
}
