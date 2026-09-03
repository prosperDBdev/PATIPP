package com.patipp.questions.internal;

import com.patipp.common.error.BadRequestException;
import com.patipp.common.error.ConflictException;
import com.patipp.common.error.NotFoundException;
import com.patipp.common.security.CurrentUser;
import com.patipp.curriculum.api.CurriculumLookup;
import com.patipp.preparations.api.SpaceAccessGuard;
import com.patipp.questions.api.QuestionDtos.CreateQuestionRequest;
import com.patipp.questions.api.QuestionDtos.QuestionPage;
import com.patipp.questions.api.QuestionDtos.QuestionResponse;
import com.patipp.questions.api.QuestionDtos.QuestionSummary;
import com.patipp.questions.api.QuestionDtos.UpdateQuestionRequest;
import com.patipp.questions.api.QuestionDtos.VersionSummary;
import com.patipp.questions.domain.ContentHash;
import com.patipp.questions.domain.Difficulty;
import com.patipp.questions.domain.Question;
import com.patipp.questions.domain.QuestionRepository;
import com.patipp.questions.domain.QuestionSource;
import com.patipp.questions.domain.QuestionStats;
import com.patipp.questions.domain.QuestionStatsRepository;
import com.patipp.questions.domain.QuestionStatus;
import com.patipp.questions.domain.QuestionType;
import com.patipp.questions.domain.QuestionVersion;
import com.patipp.questions.domain.QuestionVersionRepository;
import com.patipp.questions.domain.content.QuestionContent;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final QuestionRepository questions;
    private final QuestionVersionRepository versions;
    private final QuestionStatsRepository stats;
    private final SpaceAccessGuard accessGuard;
    private final CurriculumLookup curriculum;
    private final CurrentUser currentUser;
    private final Clock clock;

    public QuestionService(QuestionRepository questions,
                           QuestionVersionRepository versions,
                           QuestionStatsRepository stats,
                           SpaceAccessGuard accessGuard,
                           CurriculumLookup curriculum,
                           CurrentUser currentUser,
                           Clock clock) {
        this.questions = questions;
        this.versions = versions;
        this.stats = stats;
        this.accessGuard = accessGuard;
        this.curriculum = curriculum;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public QuestionPage search(UUID spaceId, UUID subjectId, UUID topicId, String type,
                               String difficulty, String status, String search,
                               int page, int size) {
        accessGuard.requireOwned(spaceId);

        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Page<Question> found = questions.findAll(
                QuestionSpecifications.filter(
                        spaceId,
                        subjectId,
                        topicId,
                        type == null ? null : parseType(type),
                        difficulty == null ? null : parseDifficulty(difficulty),
                        status == null ? null : parseStatus(status),
                        search),
                PageRequest.of(Math.max(page, 0), safeSize));

        return new QuestionPage(
                found.getContent().stream().map(this::toSummary).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Transactional(readOnly = true)
    public QuestionResponse get(UUID spaceId, UUID questionId) {
        accessGuard.requireOwned(spaceId);
        return toResponse(requireQuestion(spaceId, questionId));
    }

    @Transactional(readOnly = true)
    public List<VersionSummary> history(UUID spaceId, UUID questionId) {
        accessGuard.requireOwned(spaceId);
        Question question = requireQuestion(spaceId, questionId);
        UUID currentId = question.currentVersion() == null ? null : question.currentVersion().id();

        return versions.findByQuestionIdOrderByVersionDesc(questionId).stream()
                .map(version -> new VersionSummary(
                        version.id(), version.version(), version.stem(),
                        version.createdAt(), version.id().equals(currentId)))
                .toList();
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public QuestionResponse create(UUID spaceId, CreateQuestionRequest request) {
        accessGuard.requireWritable(spaceId);

        QuestionType type = parseType(request.type());
        Difficulty difficulty = parseDifficulty(request.difficulty());
        QuestionContent content = QuestionContent.parse(type, request.payload());

        UUID subjectId = resolveSubject(spaceId, request.subjectId(), request.topicId());
        validateTopic(spaceId, request.topicId());

        String hash = ContentHash.of(type, request.stem(), content);
        if (!questions.findLiveByHashes(spaceId, Set.of(hash)).isEmpty()) {
            throw new ConflictException("question.duplicate",
                    "This space already has a question with that wording.");
        }

        Question question = Question.create(
                spaceId, subjectId, request.topicId(), type, difficulty,
                QuestionSource.MANUAL, null, request.estimatedSeconds(),
                request.tags(), hash, currentUser.requireId());

        return toResponse(persistWithFirstVersion(
                question, request.stem(), request.explanation(), request.hints(), content));
    }

    /**
     * Applies an edit.
     *
     * <p>Metadata changes update the row. A change to the wording, explanation, hints or
     * payload inserts a <em>new version</em> instead, because attempts point at the version they
     * were answered against and rewriting one would silently change what a past score meant.
     */
    @Transactional
    public QuestionResponse update(UUID spaceId, UUID questionId, UpdateQuestionRequest request) {
        accessGuard.requireWritable(spaceId);
        Question question = requireQuestion(spaceId, questionId);
        QuestionVersion current = requireCurrentVersion(question);

        if (request.subjectId() != null || request.topicId() != null) {
            UUID topicId = request.topicId() != null ? request.topicId() : question.topicId();
            validateTopic(spaceId, topicId);
            UUID subjectId = resolveSubject(spaceId,
                    request.subjectId() != null ? request.subjectId() : question.subjectId(),
                    topicId);
            question.recategorise(subjectId, topicId);
        }
        if (request.difficulty() != null) {
            Difficulty difficulty = parseDifficulty(request.difficulty());
            question.redifficulty(difficulty);
            stats.findById(questionId).ifPresent(existing -> existing.reseedFrom(difficulty));
        }
        if (request.status() != null) {
            QuestionStatus status = parseStatus(request.status());
            if (status == QuestionStatus.ARCHIVED) {
                // Archiving also stamps archived_at and removes the question from every
                // listing, so it is its own operation rather than a status edit in disguise.
                throw new BadRequestException("question.status_invalid",
                        "Use DELETE to archive a question. Status may be DRAFT or ACTIVE.");
            }
            question.changeStatus(status);
        }
        if (request.tags() != null) {
            question.retag(request.tags());
        }
        if (request.estimatedSeconds() != null) {
            question.reestimate(request.estimatedSeconds());
        }

        boolean contentChanged = request.stem() != null
                || request.explanation() != null
                || request.hints() != null
                || request.payload() != null;

        if (contentChanged) {
            String stem = request.stem() != null ? request.stem() : current.stem();
            String explanation = request.explanation() != null
                    ? request.explanation() : current.explanation();
            List<String> hints = request.hints() != null ? request.hints() : current.hints();
            Map<String, Object> payload = request.payload() != null
                    ? request.payload() : current.payload();

            QuestionContent content = QuestionContent.parse(question.type(), payload);

            String hash = ContentHash.of(question.type(), stem, content);
            boolean clashesWithAnother = questions.findLiveByHashes(spaceId, Set.of(hash)).stream()
                    .anyMatch(other -> !other.id().equals(questionId));
            if (clashesWithAnother) {
                throw new ConflictException("question.duplicate",
                        "Another question in this space already has that wording.");
            }

            int nextVersion = versions.highestVersionOf(questionId) + 1;
            QuestionVersion version = versions.save(QuestionVersion.of(
                    questionId, nextVersion, stem, explanation, hints, content,
                    currentUser.requireId()));
            question.pointAt(version);
            question.rehash(hash);
        }

        return toResponse(question);
    }

    @Transactional
    public void archive(UUID spaceId, UUID questionId) {
        accessGuard.requireWritable(spaceId);
        requireQuestion(spaceId, questionId).archive(clock.instant());
    }

    @Transactional
    public QuestionResponse restore(UUID spaceId, UUID questionId) {
        accessGuard.requireWritable(spaceId);
        Question question = questions.findInSpace(questionId, spaceId)
                .orElseThrow(() -> new NotFoundException("question.not_found", "No such question."));

        if (question.isArchived()
                && !questions.findLiveByHashes(spaceId, Set.of(question.contentHash())).isEmpty()) {
            // The unique index only covers live rows, so a replacement may have been created
            // in the meantime. Restoring would violate it; say so clearly instead.
            throw new ConflictException("question.duplicate",
                    "A live question with that wording already exists, so this one cannot be restored.");
        }
        question.restore();
        return toResponse(question);
    }

    // ------------------------------------------------------------------ shared internals

    /**
     * Persists a new question together with its first version and its item statistics.
     *
     * <p>Ordered deliberately: the question is flushed first so the version row can reference
     * it, then the question is repointed at that version. Import uses this too, so a manually
     * authored question and an imported one produce identical rows.
     */
    Question persistWithFirstVersion(Question question, String stem, String explanation,
                                     List<String> hints, QuestionContent content) {
        questions.saveAndFlush(question);

        UUID author = currentUser.find().map(user -> user.id()).orElse(null);
        QuestionVersion version = versions.save(
                QuestionVersion.of(question.id(), 1, stem, explanation, hints, content, author));

        question.pointAt(version);
        stats.save(QuestionStats.seedFor(question));
        return question;
    }

    private Question requireQuestion(UUID spaceId, UUID questionId) {
        return questions.findInSpace(questionId, spaceId)
                .filter(question -> !question.isArchived())
                .orElseThrow(() -> new NotFoundException("question.not_found", "No such question."));
    }

    private QuestionVersion requireCurrentVersion(Question question) {
        QuestionVersion current = question.currentVersion();
        if (current == null) {
            throw new IllegalStateException(
                    "question " + question.id() + " has no current version");
        }
        return current;
    }

    /**
     * A topic always wins over an explicitly supplied subject.
     *
     * <p>A question filed under React but tagged with a CSS topic is incoherent, and the
     * topic is the more specific statement of intent, so it decides.
     */
    private UUID resolveSubject(UUID spaceId, UUID requestedSubjectId, UUID topicId) {
        if (topicId != null) {
            return curriculum.subjectOfTopic(spaceId, topicId)
                    .orElseThrow(() -> new NotFoundException("topic.not_found", "No such topic."));
        }
        if (!curriculum.subjectExists(spaceId, requestedSubjectId)) {
            throw new NotFoundException("subject.not_found", "No such subject.");
        }
        return requestedSubjectId;
    }

    private void validateTopic(UUID spaceId, UUID topicId) {
        if (topicId != null && !curriculum.topicExists(spaceId, topicId)) {
            throw new NotFoundException("topic.not_found", "No such topic.");
        }
    }

    static QuestionType parseType(String raw) {
        try {
            QuestionType type = QuestionType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
            if (!type.isImplemented()) {
                throw new BadRequestException("question.type_unsupported",
                        "Question type " + type + " is not available yet.");
            }
            return type;
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("question.type_invalid",
                    "Unknown question type \"" + raw + "\".");
        }
    }

    static Difficulty parseDifficulty(String raw) {
        try {
            return Difficulty.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("question.difficulty_invalid",
                    "Difficulty must be EASY, MEDIUM, HARD or EXPERT.");
        }
    }

    static QuestionStatus parseStatus(String raw) {
        try {
            return QuestionStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("question.status_invalid",
                    "Status must be DRAFT, ACTIVE or ARCHIVED.");
        }
    }

    private QuestionSummary toSummary(Question question) {
        QuestionVersion version = question.currentVersion();
        return new QuestionSummary(
                question.id(), question.subjectId(), question.topicId(),
                question.type().name(), question.difficulty().name(), question.status().name(),
                question.tags(), version == null ? "" : version.stem(),
                question.estimatedSeconds(), question.createdAt());
    }

    QuestionResponse toResponse(Question question) {
        QuestionVersion version = requireCurrentVersion(question);
        return new QuestionResponse(
                question.id(), question.subjectId(), question.topicId(),
                question.type().name(), question.difficulty().name(), question.status().name(),
                question.source().name(), question.estimatedSeconds(), question.tags(),
                version.version(), version.stem(), version.explanation(), version.hints(),
                version.payload(), question.createdAt(), question.updatedAt());
    }
}
