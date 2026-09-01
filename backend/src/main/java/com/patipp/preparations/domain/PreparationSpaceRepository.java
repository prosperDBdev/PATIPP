package com.patipp.preparations.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every method takes {@code userId}. That is not defensive duplication of the service-layer
 * check - it is the point. A finder that can be called without an owner is a finder that
 * will eventually be called without an owner, and the resulting bug leaks another user's
 * preparation space. Making ownership a required parameter means the mistake cannot be
 * expressed.
 */
public interface PreparationSpaceRepository extends JpaRepository<PreparationSpace, UUID> {

    @Query("""
            select s from PreparationSpace s
            join fetch s.preparationType
            where s.id = :id and s.userId = :userId
            """)
    Optional<PreparationSpace> findOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("""
            select s from PreparationSpace s
            join fetch s.preparationType
            where s.userId = :userId and s.archivedAt is null
            order by s.createdAt desc
            """)
    List<PreparationSpace> findActiveForUser(@Param("userId") UUID userId);

    @Query("""
            select s from PreparationSpace s
            join fetch s.preparationType
            where s.userId = :userId
            order by s.createdAt desc
            """)
    List<PreparationSpace> findAllForUser(@Param("userId") UUID userId);

    @Query("""
            select count(s) > 0 from PreparationSpace s
            where s.userId = :userId and lower(s.name) = lower(:name) and s.archivedAt is null
            """)
    boolean existsLiveWithName(@Param("userId") UUID userId, @Param("name") String name);

    @Query("""
            select count(s) > 0 from PreparationSpace s
            where s.userId = :userId and lower(s.name) = lower(:name)
              and s.archivedAt is null and s.id <> :excludingId
            """)
    boolean existsOtherLiveWithName(@Param("userId") UUID userId,
                                    @Param("name") String name,
                                    @Param("excludingId") UUID excludingId);
}
