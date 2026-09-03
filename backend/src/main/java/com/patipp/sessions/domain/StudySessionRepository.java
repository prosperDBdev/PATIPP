package com.patipp.sessions.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudySessionRepository extends JpaRepository<StudySession, UUID> {

    @Query("""
            select s from StudySession s
            where s.id = :id and s.preparationSpaceId = :spaceId and s.userId = :userId
            """)
    Optional<StudySession> findOwned(@Param("id") UUID id,
                                     @Param("spaceId") UUID spaceId,
                                     @Param("userId") UUID userId);

    /**
     * Sessions left open in this space.
     *
     * <p>Returned as a list rather than a single result on purpose: two tabs, or a crash
     * mid-session, can genuinely leave more than one. The caller resumes the newest and the
     * others stay resumable rather than being silently discarded.
     */
    @Query("""
            select s from StudySession s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
              and s.status = com.patipp.sessions.domain.SessionStatus.IN_PROGRESS
            order by s.startedAt desc
            """)
    List<StudySession> findInProgress(@Param("userId") UUID userId, @Param("spaceId") UUID spaceId);

    @Query("""
            select s from StudySession s
            where s.userId = :userId and s.preparationSpaceId = :spaceId
            order by s.startedAt desc
            """)
    Page<StudySession> findHistory(@Param("userId") UUID userId,
                                   @Param("spaceId") UUID spaceId,
                                   Pageable pageable);
}
