-- =============================================================================
-- V7 - Attempts can be graded partly by the server and partly by the learner.
--
-- Phase 5.5 adds three code-practice formats. Two of them cannot be graded by
-- the application alone:
--
--   CODING            solved by hand in the learner's own editor, then graded
--                     against the author's rubric -> SELF
--   DEBUGGING         the line is checked by the server, the explanation is
--                     judged by the learner -> MIXED
--   OUTPUT_PREDICTION a string comparison -> AUTO, no change needed
--
-- MIXED is the new value. The vocabulary is widened rather than DEBUGGING being
-- squeezed into SELF, because a readiness figure has to be able to say how much
-- of itself rests on self-assessment, and calling a half-checked answer entirely
-- self-graded would understate the evidence behind it.
--
-- A widened CHECK needs no data migration: every existing row is AUTO and stays
-- valid. Dropped and recreated rather than added alongside, so there is exactly
-- one constraint describing the column.
-- =============================================================================

ALTER TABLE question_attempts
    DROP CONSTRAINT question_attempts_evaluated_by_known;

ALTER TABLE question_attempts
    ADD CONSTRAINT question_attempts_evaluated_by_known
        CHECK (evaluated_by IN ('AUTO', 'SELF', 'MIXED', 'AI'));

COMMENT ON COLUMN question_attempts.evaluated_by IS
    'Who graded this attempt: AUTO (answer key), SELF (learner against a rubric), '
    'MIXED (part each), AI (Phase 8). Recorded because a score is only interpretable '
    'alongside how it was arrived at.';
