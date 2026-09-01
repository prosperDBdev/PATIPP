package com.patipp.curriculum.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRepository extends JpaRepository<Topic, UUID> {

    @Query("""
            select t from Topic t
            where t.id = :id and t.preparationSpaceId = :spaceId
            """)
    Optional<Topic> findInSpace(@Param("id") UUID id, @Param("spaceId") UUID spaceId);

    /**
     * Every live topic in the space, ordered so a tree can be assembled in one pass:
     * parents always precede their children because depth is the first sort key.
     */
    @Query("""
            select t from Topic t
            where t.preparationSpaceId = :spaceId and t.archivedAt is null
            order by t.depth asc, t.position asc, t.name asc
            """)
    List<Topic> findLiveInSpace(@Param("spaceId") UUID spaceId);

    @Query("""
            select t from Topic t
            where t.preparationSpaceId = :spaceId and t.subjectId = :subjectId
              and t.archivedAt is null
            order by t.depth asc, t.position asc, t.name asc
            """)
    List<Topic> findLiveInSubject(@Param("spaceId") UUID spaceId, @Param("subjectId") UUID subjectId);

    @Query("""
            select t from Topic t
            where t.preparationSpaceId = :spaceId and t.parentTopicId = :parentId
              and t.archivedAt is null
            """)
    List<Topic> findLiveChildren(@Param("spaceId") UUID spaceId, @Param("parentId") UUID parentId);

    @Query("""
            select count(t) > 0 from Topic t
            where t.subjectId = :subjectId and lower(t.name) = lower(:name)
              and t.archivedAt is null
              and ((:parentId is null and t.parentTopicId is null) or t.parentTopicId = :parentId)
            """)
    boolean existsLiveSiblingWithName(@Param("subjectId") UUID subjectId,
                                      @Param("parentId") UUID parentId,
                                      @Param("name") String name);

    @Query("""
            select count(t) > 0 from Topic t
            where t.subjectId = :subjectId and lower(t.name) = lower(:name)
              and t.archivedAt is null and t.id <> :excludingId
              and ((:parentId is null and t.parentTopicId is null) or t.parentTopicId = :parentId)
            """)
    boolean existsOtherLiveSiblingWithName(@Param("subjectId") UUID subjectId,
                                           @Param("parentId") UUID parentId,
                                           @Param("name") String name,
                                           @Param("excludingId") UUID excludingId);

    @Query("""
            select coalesce(max(t.position), -1) from Topic t
            where t.subjectId = :subjectId
              and ((:parentId is null and t.parentTopicId is null) or t.parentTopicId = :parentId)
            """)
    short maxSiblingPosition(@Param("subjectId") UUID subjectId, @Param("parentId") UUID parentId);
}
