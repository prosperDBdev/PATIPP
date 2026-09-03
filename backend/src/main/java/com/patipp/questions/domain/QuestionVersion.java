package com.patipp.questions.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import com.patipp.questions.domain.content.QuestionContent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable revision of a question's wording and answer data.
 *
 * <p>An edit inserts a new row and repoints {@link Question#currentVersion}; it never
 * updates an existing one. Every attempt records the exact version it was answered against,
 * so correcting a typo cannot retroactively change what a past score meant.
 *
 * <p>{@code payload} is stored as jsonb and is always parsed through
 * {@link QuestionContent#parse} before use, so the flexibility of schemaless storage never
 * turns into unchecked data.
 */
@Entity
@Table(name = "question_versions")
public class QuestionVersion extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "stem", nullable = false, updatable = false)
    private String stem;

    @Column(name = "explanation", updatable = false)
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hints", nullable = false, updatable = false)
    private List<String> hints = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private Map<String, Object> payload = Map.of();

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected QuestionVersion() {
        // for JPA
    }

    public static QuestionVersion of(UUID questionId, int version, String stem, String explanation,
                                     List<String> hints, QuestionContent content, UUID createdBy) {
        QuestionVersion row = new QuestionVersion();
        row.id = UuidV7.generate();
        row.questionId = questionId;
        row.version = version;
        row.stem = stem.strip();
        row.explanation = (explanation == null || explanation.isBlank()) ? null : explanation.strip();
        row.hints = hints == null ? List.of() : List.copyOf(hints);
        row.payload = content.toPayload();
        row.createdBy = createdBy;
        return row;
    }

    public UUID id() {
        return id;
    }

    /** Required by Persistable so Spring Data can tell an insert from an update. */
    @Override
    public UUID getId() {
        return id;
    }

    public UUID questionId() {
        return questionId;
    }

    public int version() {
        return version;
    }

    public String stem() {
        return stem;
    }

    public String explanation() {
        return explanation;
    }

    public List<String> hints() {
        return hints == null ? List.of() : hints;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Re-parses the stored payload into its typed form for the given question format. */
    public QuestionContent content(QuestionType type) {
        return QuestionContent.parse(type, payload);
    }
}
