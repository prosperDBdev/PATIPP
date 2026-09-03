package com.patipp.questions.domain;

/**
 * DRAFT is authored but withheld from sessions; ACTIVE is in play; ARCHIVED is retired but
 * retained, because attempts will reference it and deleting it would corrupt history.
 */
public enum QuestionStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
