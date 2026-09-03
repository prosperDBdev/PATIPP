package com.patipp.questions.internal;

import com.patipp.questions.domain.Difficulty;
import com.patipp.questions.domain.Question;
import com.patipp.questions.domain.QuestionStatus;
import com.patipp.questions.domain.QuestionType;
import jakarta.persistence.criteria.JoinType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the question-list filter as a specification.
 *
 * <p>Only the predicates the caller actually asked for reach the SQL. Besides being the
 * clearer way to express optional filters, it sidesteps a specific PostgreSQL failure: a
 * null bound to an untyped parameter position is inferred as {@code bytea}, so a query of
 * the form {@code (:search is null or lower(stem) like ...)} fails outright with
 * {@code function lower(bytea) does not exist} the moment the filter is omitted.
 */
final class QuestionSpecifications {

    private QuestionSpecifications() {
    }

    static Specification<Question> filter(UUID spaceId, UUID subjectId, UUID topicId,
                                          QuestionType type, Difficulty difficulty,
                                          QuestionStatus status, String search) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            // Never optional. The partition key is the whole point.
            predicates.add(builder.equal(root.get("preparationSpaceId"), spaceId));
            predicates.add(builder.isNull(root.get("archivedAt")));

            if (subjectId != null) {
                predicates.add(builder.equal(root.get("subjectId"), subjectId));
            }
            if (topicId != null) {
                predicates.add(builder.equal(root.get("topicId"), topicId));
            }
            if (type != null) {
                predicates.add(builder.equal(root.get("type"), type));
            }
            if (difficulty != null) {
                predicates.add(builder.equal(root.get("difficulty"), difficulty));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (search != null && !search.isBlank()) {
                var version = root.join("currentVersion", JoinType.LEFT);
                predicates.add(builder.like(
                        builder.lower(version.get("stem")),
                        "%" + search.strip().toLowerCase(Locale.ROOT) + "%"));
            }

            if (query != null) {
                // Sorting is not meaningful in the count query Spring Data issues alongside
                // the page query, and including it there provokes a needless join.
                if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                    query.orderBy(builder.desc(root.get("createdAt")));
                }
            }

            return builder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
