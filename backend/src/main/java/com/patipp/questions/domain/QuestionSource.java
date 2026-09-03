package com.patipp.questions.domain;

/**
 * Where a question came from. AI is recorded separately from MANUAL so that generated
 * content can always be found, reviewed and, if it turns out to be poor, removed as a
 * group.
 */
public enum QuestionSource {
    MANUAL,
    IMPORT,
    AI,
    SEED
}
