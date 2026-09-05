package com.patipp.sessions.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExamTemplateRepository extends JpaRepository<ExamTemplate, UUID> {

    @Query("""
            select t from ExamTemplate t
            where t.id = :id and t.preparationSpaceId = :spaceId and t.archivedAt is null
            """)
    Optional<ExamTemplate> findInSpace(@Param("id") UUID id, @Param("spaceId") UUID spaceId);

    /** Most-used first, then most recently created, so the useful one is at the top. */
    @Query("""
            select t from ExamTemplate t
            where t.preparationSpaceId = :spaceId and t.archivedAt is null
            order by t.timesUsed desc, t.createdAt desc
            """)
    List<ExamTemplate> findLiveInSpace(@Param("spaceId") UUID spaceId);

    @Query("""
            select count(t) > 0 from ExamTemplate t
            where t.preparationSpaceId = :spaceId and lower(t.name) = lower(:name)
              and t.archivedAt is null
            """)
    boolean existsLiveWithName(@Param("spaceId") UUID spaceId, @Param("name") String name);
}
