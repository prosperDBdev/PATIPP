package com.patipp.questions.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;

/**
 * As everywhere else, {@code spaceId} is a required parameter rather than an optional
 * filter: a finder that can be called without it will eventually be called without it, and
 * the result would be another space's question bank.
 */
public interface QuestionRepository
        extends JpaRepository<Question, UUID>, JpaSpecificationExecutor<Question> {

    @Query("""
            select q from Question q
            left join fetch q.currentVersion
            where q.id = :id and q.preparationSpaceId = :spaceId
            """)
    Optional<Question> findInSpace(@Param("id") UUID id, @Param("spaceId") UUID spaceId);

    /**
     * The browse and filter query behind the question list.
     *
     * <p>Built as a {@link Specification} rather than one JPQL query with nullable
     * parameters. The nullable-parameter form reads well but breaks on PostgreSQL: a null
     * bound to an untyped position arrives as {@code bytea}, and the first function applied
     * to it fails with {@code function lower(bytea) does not exist}. A specification only
     * emits the predicates that are actually in play, so the problem cannot arise.
     *
     * <p>The entity graph pulls the current version in the same query; without it the list
     * would issue one extra select per row to render the stems.
     */
    @Override
    @EntityGraph(attributePaths = "currentVersion")
    Page<Question> findAll(Specification<Question> specification, Pageable pageable);

    /** Duplicate detection on import. Archived rows are ignored so a deletion can be undone. */
    @Query("""
            select q from Question q
            where q.preparationSpaceId = :spaceId and q.contentHash in :hashes
              and q.archivedAt is null
            """)
    List<Question> findLiveByHashes(@Param("spaceId") UUID spaceId,
                                    @Param("hashes") Set<String> hashes);

    @Query("""
            select q from Question q
            left join fetch q.currentVersion
            where q.preparationSpaceId = :spaceId and q.archivedAt is null
            order by q.createdAt asc
            """)
    List<Question> findAllLiveInSpace(@Param("spaceId") UUID spaceId);

    @Query("select count(q) from Question q where q.preparationSpaceId = :spaceId and q.archivedAt is null")
    long countLiveInSpace(@Param("spaceId") UUID spaceId);

    /** Powers the per-subject counts on the space overview without loading any questions. */
    @Query("""
            select q.subjectId, count(q) from Question q
            where q.preparationSpaceId = :spaceId and q.archivedAt is null
            group by q.subjectId
            """)
    List<Object[]> countBySubject(@Param("spaceId") UUID spaceId);
}
