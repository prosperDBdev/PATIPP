package com.patipp.analytics.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyActivityRepository extends JpaRepository<DailyActivity, UUID> {

    /**
     * One space's row for a day.
     *
     * <p>Separate from {@link #findGlobalDay} because {@code = null} is never true in SQL, so a
     * single query with a nullable parameter would silently return nothing for the roll-up.
     */
    @Query("""
            select a from DailyActivity a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
              and a.activityDate = :date
            """)
    Optional<DailyActivity> findSpaceDay(@Param("userId") UUID userId,
                                         @Param("spaceId") UUID spaceId,
                                         @Param("date") LocalDate date);

    @Query("""
            select a from DailyActivity a
            where a.userId = :userId and a.preparationSpaceId is null
              and a.activityDate = :date
            """)
    Optional<DailyActivity> findGlobalDay(@Param("userId") UUID userId,
                                          @Param("date") LocalDate date);

    /** A space's recent days, newest first, for the heatmap and the consistency component. */
    @Query("""
            select a from DailyActivity a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
              and a.activityDate >= :from
            order by a.activityDate desc
            """)
    List<DailyActivity> findSpaceSince(@Param("userId") UUID userId,
                                       @Param("spaceId") UUID spaceId,
                                       @Param("from") LocalDate from);

    /** Every day across every space, for the global streak. */
    @Query("""
            select a from DailyActivity a
            where a.userId = :userId and a.preparationSpaceId is null
              and a.activityDate >= :from
            order by a.activityDate desc
            """)
    List<DailyActivity> findGlobalSince(@Param("userId") UUID userId,
                                        @Param("from") LocalDate from);

    /** Clears one space's activity, so it can be rebuilt from the attempt log. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from DailyActivity a
            where a.userId = :userId and a.preparationSpaceId = :spaceId
            """)
    int deleteForSpace(@Param("userId") UUID userId, @Param("spaceId") UUID spaceId);
}
