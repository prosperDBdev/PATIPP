package com.patipp.learning.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningStateRepository extends JpaRepository<LearningState, UUID> {

    @Query("""
            select s from LearningState s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
              and s.questionId = :questionId
            """)
    Optional<LearningState> findForItem(@Param("userId") UUID userId,
                                        @Param("spaceId") UUID spaceId,
                                        @Param("questionId") UUID questionId);

    /** The whole schedule for one space, read once when a learner model is built. */
    @Query("""
            select s from LearningState s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
            """)
    List<LearningState> findForLearner(@Param("userId") UUID userId,
                                       @Param("spaceId") UUID spaceId);

    /**
     * What is due, most overdue first.
     *
     * <p>Ordered by {@code due_at} and tie-broken by id, because a review queue that reorders
     * itself between two reads is one a learner cannot work through.
     */
    @Query("""
            select s from LearningState s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
              and s.phase <> 'SUSPENDED'
              and s.dueAt is not null and s.dueAt <= :now
            order by s.dueAt asc, s.id asc
            """)
    List<LearningState> findDue(@Param("userId") UUID userId,
                                @Param("spaceId") UUID spaceId,
                                @Param("now") Instant now,
                                Pageable pageable);

    @Query("""
            select count(s) from LearningState s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
              and s.phase <> 'SUSPENDED'
              and s.dueAt is not null and s.dueAt <= :now
            """)
    int countDue(@Param("userId") UUID userId,
                 @Param("spaceId") UUID spaceId,
                 @Param("now") Instant now);

    /**
     * How many are due and badly overdue, for the review-debt figure.
     *
     * <p>Overdue by more than the interval that was scheduled means the interval no longer
     * describes anything: at that point the item is not so much due as forgotten.
     */
    @Query("""
            select count(s) from LearningState s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
              and s.phase <> 'SUSPENDED'
              and s.dueAt is not null and s.dueAt <= :now
              and s.lapses >= 2
            """)
    int countStruggling(@Param("userId") UUID userId,
                        @Param("spaceId") UUID spaceId,
                        @Param("now") Instant now);

    /** Clears the schedule for one learner and space, so it can be rebuilt from the log. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from LearningState s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
            """)
    int deleteForLearner(@Param("userId") UUID userId, @Param("spaceId") UUID spaceId);
}
