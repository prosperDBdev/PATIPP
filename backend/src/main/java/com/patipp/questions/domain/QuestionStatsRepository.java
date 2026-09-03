package com.patipp.questions.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionStatsRepository extends JpaRepository<QuestionStats, UUID> {

    @Query("select s from QuestionStats s where s.questionId in :questionIds")
    List<QuestionStats> findAllByQuestionIds(@Param("questionIds") List<UUID> questionIds);

    @Query("select s from QuestionStats s where s.preparationSpaceId = :spaceId")
    List<QuestionStats> findAllInSpace(@Param("spaceId") UUID spaceId);
}
