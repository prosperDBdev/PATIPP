package com.patipp.learning.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicMasteryRepository extends JpaRepository<TopicMastery, UUID> {

    /** The whole learner model for one space, which is one index scan. */
    @Query("""
            select m from TopicMastery m
            where m.userId = :userId and m.preparationSpaceId = :spaceId
            """)
    List<TopicMastery> findForLearner(@Param("userId") UUID userId,
                                      @Param("spaceId") UUID spaceId);

    /**
     * The bucket for a topic, or - when {@code topicId} is null - the subject's untagged
     * bucket.
     *
     * <p>Two queries rather than one with {@code is null} handling, because {@code = null} is
     * never true in SQL and a single query would silently return nothing for untagged
     * questions.
     */
    @Query("""
            select m from TopicMastery m
            where m.userId = :userId and m.preparationSpaceId = :spaceId
              and m.topicId = :topicId
            """)
    Optional<TopicMastery> findForTopic(@Param("userId") UUID userId,
                                        @Param("spaceId") UUID spaceId,
                                        @Param("topicId") UUID topicId);

    @Query("""
            select m from TopicMastery m
            where m.userId = :userId and m.preparationSpaceId = :spaceId
              and m.subjectId = :subjectId and m.topicId is null
            """)
    Optional<TopicMastery> findUntagged(@Param("userId") UUID userId,
                                        @Param("spaceId") UUID spaceId,
                                        @Param("subjectId") UUID subjectId);

    /**
     * Clears the derived state for one learner and space, so it can be rebuilt.
     *
     * <p>Safe in a way that deleting from {@code question_attempts} is not: this table holds
     * nothing that cannot be recomputed from the log.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from TopicMastery m
            where m.userId = :userId and m.preparationSpaceId = :spaceId
            """)
    int deleteForLearner(@Param("userId") UUID userId, @Param("spaceId") UUID spaceId);
}
