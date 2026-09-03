package com.patipp.questions.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The stable identity of a question, plus everything you filter or sort on.
 *
 * <p>The wording lives in {@link QuestionVersion}; this row is what survives an edit. The
 * split exists so that an attempt recorded in January can point at the exact text it was
 * answered against, even after a typo is fixed in March.
 *
 * <p>Note what is <em>not</em> here: no correctness counts for a user, no review schedule, no
 * mastery. A question carries no per-learner state at all, which is what keeps a bank
 * exportable, importable and shareable between preparation spaces.
 */
@Entity
@Table(name = "questions")
public class Question extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "topic_id")
    private UUID topicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 32)
    private QuestionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 16)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private QuestionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private QuestionSource source;

    @Column(name = "source_ref")
    private String sourceRef;

    @Column(name = "estimated_seconds", nullable = false)
    private int estimatedSeconds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", nullable = false)
    private String[] tags = new String[0];

    /**
     * Null only for the instant between inserting the question and inserting its first
     * version, which happens inside one transaction.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_version_id")
    private QuestionVersion currentVersion;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Question() {
        // for JPA
    }

    public static Question create(UUID preparationSpaceId, UUID subjectId, UUID topicId,
                                  QuestionType type, Difficulty difficulty, QuestionSource source,
                                  String sourceRef, Integer estimatedSeconds, List<String> tags,
                                  String contentHash, UUID createdBy) {
        Question question = new Question();
        question.id = UuidV7.generate();
        question.preparationSpaceId = preparationSpaceId;
        question.subjectId = subjectId;
        question.topicId = topicId;
        question.type = type;
        question.difficulty = difficulty;
        question.status = QuestionStatus.ACTIVE;
        question.source = source;
        question.sourceRef = sourceRef;
        question.estimatedSeconds = estimatedSeconds == null || estimatedSeconds <= 0
                ? type.defaultEstimatedSeconds()
                : estimatedSeconds;
        question.tags = normaliseTags(tags);
        question.contentHash = contentHash;
        question.createdBy = createdBy;
        return question;
    }

    /**
     * Lower-cases, de-duplicates and orders tags.
     *
     * <p>Tags are a filter, and a filter where "React" and "react" are different values is a
     * filter that quietly hides half your questions.
     */
    static String[] normaliseTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new String[0];
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : raw) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            unique.add(tag.strip().toLowerCase(Locale.ROOT));
        }
        return unique.toArray(new String[0]);
    }

    public UUID id() {
        return id;
    }

    /** Required by Persistable so Spring Data can tell an insert from an update. */
    @Override
    public UUID getId() {
        return id;
    }

    public UUID preparationSpaceId() {
        return preparationSpaceId;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public UUID topicId() {
        return topicId;
    }

    public QuestionType type() {
        return type;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public QuestionStatus status() {
        return status;
    }

    public QuestionSource source() {
        return source;
    }

    public String sourceRef() {
        return sourceRef;
    }

    public int estimatedSeconds() {
        return estimatedSeconds;
    }

    public List<String> tags() {
        return tags == null ? List.of() : Arrays.asList(tags);
    }

    public QuestionVersion currentVersion() {
        return currentVersion;
    }

    public String contentHash() {
        return contentHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void pointAt(QuestionVersion version) {
        this.currentVersion = version;
    }

    public void recategorise(UUID newSubjectId, UUID newTopicId) {
        this.subjectId = newSubjectId;
        this.topicId = newTopicId;
    }

    public void redifficulty(Difficulty newDifficulty) {
        this.difficulty = newDifficulty;
    }

    public void changeStatus(QuestionStatus newStatus) {
        this.status = newStatus;
    }

    public void retag(List<String> newTags) {
        this.tags = normaliseTags(newTags);
    }

    public void reestimate(int seconds) {
        this.estimatedSeconds = seconds;
    }

    public void rehash(String newContentHash) {
        this.contentHash = newContentHash;
    }

    /**
     * Soft delete. Attempts will reference this row from Phase 3 onwards, so a hard delete
     * would either fail on a foreign key or destroy the history that every derived number is
     * rebuilt from.
     */
    public void archive(Instant when) {
        if (this.archivedAt == null) {
            this.archivedAt = when;
            this.status = QuestionStatus.ARCHIVED;
        }
    }

    public void restore() {
        this.archivedAt = null;
        this.status = QuestionStatus.ACTIVE;
    }
}
