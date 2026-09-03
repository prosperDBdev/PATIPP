package com.patipp.questions.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, UUID> {

    /** Full edit history, newest first. Attempts pin a specific row from this list. */
    List<QuestionVersion> findByQuestionIdOrderByVersionDesc(UUID questionId);

    @Query("select coalesce(max(v.version), 0) from QuestionVersion v where v.questionId = :questionId")
    int highestVersionOf(@Param("questionId") UUID questionId);

    Optional<QuestionVersion> findByQuestionIdAndVersion(UUID questionId, int version);
}
