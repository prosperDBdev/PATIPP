package com.patipp.curriculum.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code preparationSpaceId} is a required parameter on every method, never an optional
 * filter. A query that can be written without it is a query that will one day read another
 * space's curriculum.
 */
public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    @Query("""
            select s from Subject s
            where s.id = :id and s.preparationSpaceId = :spaceId
            """)
    Optional<Subject> findInSpace(@Param("id") UUID id, @Param("spaceId") UUID spaceId);

    @Query("""
            select s from Subject s
            where s.preparationSpaceId = :spaceId and s.archivedAt is null
            order by s.position asc, s.name asc
            """)
    List<Subject> findLiveInSpace(@Param("spaceId") UUID spaceId);

    @Query("""
            select count(s) > 0 from Subject s
            where s.preparationSpaceId = :spaceId and lower(s.name) = lower(:name)
              and s.archivedAt is null
            """)
    boolean existsLiveWithName(@Param("spaceId") UUID spaceId, @Param("name") String name);

    @Query("""
            select count(s) > 0 from Subject s
            where s.preparationSpaceId = :spaceId and lower(s.name) = lower(:name)
              and s.archivedAt is null and s.id <> :excludingId
            """)
    boolean existsOtherLiveWithName(@Param("spaceId") UUID spaceId,
                                    @Param("name") String name,
                                    @Param("excludingId") UUID excludingId);

    @Query("select coalesce(max(s.position), -1) from Subject s where s.preparationSpaceId = :spaceId")
    short maxPosition(@Param("spaceId") UUID spaceId);
}
