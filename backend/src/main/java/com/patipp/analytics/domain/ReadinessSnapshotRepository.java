package com.patipp.analytics.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReadinessSnapshotRepository extends JpaRepository<ReadinessSnapshot, UUID> {

    @Query("""
            select s from ReadinessSnapshot s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
              and s.capturedOn = :on
            """)
    Optional<ReadinessSnapshot> findForDay(@Param("userId") UUID userId,
                                           @Param("spaceId") UUID spaceId,
                                           @Param("on") LocalDate on);

    /**
     * The most recent snapshot before a given day, for the deltas.
     *
     * <p>Before rather than "yesterday": a learner who skipped four days should be told how they
     * compare with the last time they were measured, not with a day that has no snapshot.
     */
    @Query("""
            select s from ReadinessSnapshot s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
              and s.capturedOn < :before
            order by s.capturedOn desc
            """)
    List<ReadinessSnapshot> findPrevious(@Param("userId") UUID userId,
                                         @Param("spaceId") UUID spaceId,
                                         @Param("before") LocalDate before,
                                         Pageable pageable);

    @Query("""
            select s from ReadinessSnapshot s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
            order by s.capturedOn desc
            """)
    List<ReadinessSnapshot> findHistory(@Param("userId") UUID userId,
                                        @Param("spaceId") UUID spaceId,
                                        Pageable pageable);
}
