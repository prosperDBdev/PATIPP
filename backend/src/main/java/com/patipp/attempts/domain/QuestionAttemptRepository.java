package com.patipp.attempts.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and appends. There is deliberately no update or delete method here.
 *
 * <p>{@code JpaRepository} does inherit {@code delete} and {@code save}, but the entity is
 * {@link org.hibernate.annotations.Immutable} so Hibernate never issues an UPDATE, and a
 * database trigger refuses one that arrives by any other path. The API surface being narrow
 * is a hint; the trigger is the guarantee.
 */
public interface QuestionAttemptRepository extends JpaRepository<QuestionAttempt, UUID> {

    /** How many times this learner has already answered this question, in this space. */
    @Query("""
            select count(a) from QuestionAttempt a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
              and a.questionId = :questionId
            """)
    int countPriorAttempts(@Param("userId") UUID userId,
                           @Param("spaceId") UUID spaceId,
                           @Param("questionId") UUID questionId);

    @Query("""
            select a from QuestionAttempt a
            where a.sessionId = :sessionId
            order by a.createdAt asc
            """)
    List<QuestionAttempt> findForSession(@Param("sessionId") UUID sessionId);

    @Query("""
            select a from QuestionAttempt a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
              and a.questionId = :questionId
            order by a.createdAt desc
            """)
    List<QuestionAttempt> findHistory(@Param("userId") UUID userId,
                                      @Param("spaceId") UUID spaceId,
                                      @Param("questionId") UUID questionId);

    @Query("""
            select a from QuestionAttempt a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
            order by a.createdAt desc
            """)
    Page<QuestionAttempt> findRecent(@Param("userId") UUID userId,
                                     @Param("spaceId") UUID spaceId,
                                     Pageable pageable);

    /**
     * Mean answered difficulty per subject, 1 EASY to 4 EXPERT.
     *
     * <p>So accuracy can be weighted by how hard the questions were. Without it, a score can be
     * inflated by drilling the easy end of the bank, and the readiness figure would reward exactly
     * the practice that helps least.
     */
    @Query("""
            select a.subjectId,
                   avg(case a.difficulty
                         when 'EASY' then 1.0
                         when 'MEDIUM' then 2.0
                         when 'HARD' then 3.0
                         else 4.0 end)
            from QuestionAttempt a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
              and a.subjectId is not null
            group by a.subjectId
            """)
    List<Object[]> meanDifficultyRows(@Param("userId") UUID userId,
                                      @Param("spaceId") UUID spaceId);

    /** The same, as a map, so callers never touch an {@code Object[]}. */
    default java.util.Map<UUID, Double> meanDifficultyBySubject(UUID userId, UUID spaceId) {
        java.util.Map<UUID, Double> bySubject = new java.util.LinkedHashMap<>();
        for (Object[] row : meanDifficultyRows(userId, spaceId)) {
            if (row[0] instanceof UUID subjectId && row[1] instanceof Number mean) {
                bySubject.put(subjectId, mean.doubleValue());
            }
        }
        return bySubject;
    }

    /**
     * Every attempt this learner has made in this space, oldest first.
     *
     * <p>Replay order, deliberately: this feeds both the learner model and the rebuild of
     * derived state, and a rebuild that processed answers out of order would produce
     * different numbers from the incremental path it is meant to reproduce.
     */
    @Query("""
            select a from QuestionAttempt a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
            order by a.createdAt asc, a.id asc
            """)
    List<QuestionAttempt> findAllForLearner(@Param("userId") UUID userId,
                                            @Param("spaceId") UUID spaceId);

    @Query("""
            select count(a) from QuestionAttempt a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
            """)
    long countInSpace(@Param("userId") UUID userId, @Param("spaceId") UUID spaceId);
}
