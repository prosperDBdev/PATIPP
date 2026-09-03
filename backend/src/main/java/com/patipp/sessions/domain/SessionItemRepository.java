package com.patipp.sessions.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionItemRepository extends JpaRepository<SessionItem, UUID> {

    @Query("select i from SessionItem i where i.sessionId = :sessionId order by i.position asc")
    List<SessionItem> findForSession(@Param("sessionId") UUID sessionId);

    @Query("select i from SessionItem i where i.sessionId = :sessionId and i.position = :position")
    Optional<SessionItem> findAt(@Param("sessionId") UUID sessionId,
                                 @Param("position") short position);

    /**
     * The next question to serve: the lowest position not yet answered.
     *
     * <p>Practice moves forward one at a time, so "next" is simply the first unanswered slot.
     * Exam mode in Phase 4 lets the user jump around instead, which is why this is a query
     * about state rather than a counter on the session.
     */
    @Query("""
            select i from SessionItem i
            where i.sessionId = :sessionId
              and i.state <> com.patipp.sessions.domain.ItemState.ANSWERED
            order by i.position asc
            limit 1
            """)
    Optional<SessionItem> findNextUnanswered(@Param("sessionId") UUID sessionId);
}
