-- =============================================================================
-- V4 - The session engine and the attempt log.
--
-- One sessions table with a `mode`, not a table per kind of session. An exam is
-- a session with a deadline and deferred feedback; an interview is a session
-- with conversational turns; a flashcard review is a session with grades. The
-- moment "exam" becomes its own entity, scoring, history and analytics all get
-- written three times.
--
-- question_attempts is the source of truth for everything derived later:
-- mastery, review schedules, readiness. It is append-only, and that is enforced
-- by a trigger rather than by everyone remembering.
-- =============================================================================

CREATE TABLE study_sessions (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    preparation_space_id uuid         NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    mode                 varchar(24)  NOT NULL,
    status               varchar(16)  NOT NULL DEFAULT 'IN_PROGRESS',

    -- What was asked for: length, topic and difficulty filters, feedback setting.
    -- Kept so a session can be explained and repeated later.
    config               jsonb        NOT NULL DEFAULT '{}'::jsonb,

    -- Server-set. deadline_at stays null for practice and is filled by exam mode
    -- in Phase 4, where the client must not be trusted with the clock.
    started_at           timestamptz  NOT NULL DEFAULT now(),
    deadline_at          timestamptz,
    submitted_at         timestamptz,

    -- Excludes idle gaps; summed from per-item time rather than wall clock, so
    -- walking away mid-session does not inflate study time.
    active_ms            bigint       NOT NULL DEFAULT 0,

    total_items          int          NOT NULL DEFAULT 0,
    answered_count       int          NOT NULL DEFAULT 0,
    correct_count        int          NOT NULL DEFAULT 0,

    score                numeric(5,2),
    score_breakdown      jsonb,

    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT study_sessions_mode_known CHECK (mode IN (
        'PRACTICE', 'EXAM', 'INTERVIEW', 'FLASHCARD_REVIEW', 'DRILL')),
    CONSTRAINT study_sessions_status_known CHECK (status IN (
        'IN_PROGRESS', 'SUBMITTED', 'ABANDONED', 'EXPIRED')),
    CONSTRAINT study_sessions_counts_sane CHECK (
        total_items >= 0 AND answered_count >= 0 AND correct_count >= 0
        AND answered_count <= total_items AND correct_count <= answered_count),
    CONSTRAINT study_sessions_score_range CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    CONSTRAINT study_sessions_deadline_sane CHECK (deadline_at IS NULL OR deadline_at > started_at),
    CONSTRAINT study_sessions_config_object CHECK (jsonb_typeof(config) = 'object'),

    -- Target of the composite foreign key from session_items.
    CONSTRAINT study_sessions_id_space_unique UNIQUE (id, preparation_space_id)
);

CREATE TRIGGER study_sessions_set_updated_at
    BEFORE UPDATE ON study_sessions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_study_sessions_user_space
    ON study_sessions (user_id, preparation_space_id, started_at DESC);

-- Resuming: at most a handful of rows, but hit on every session screen.
CREATE INDEX idx_study_sessions_in_progress
    ON study_sessions (user_id, preparation_space_id)
    WHERE status = 'IN_PROGRESS';


-- =============================================================================
-- The questions served in a session, in order.
-- =============================================================================

CREATE TABLE session_items (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id           uuid         NOT NULL REFERENCES study_sessions (id) ON DELETE CASCADE,
    preparation_space_id uuid         NOT NULL,

    position             smallint     NOT NULL,

    question_id          uuid         NOT NULL REFERENCES questions (id) ON DELETE RESTRICT,
    -- Pinned. An edit to the question after this point creates a new version and
    -- leaves this one exactly as it was answered.
    question_version_id  uuid         NOT NULL REFERENCES question_versions (id) ON DELETE RESTRICT,

    state                varchar(20)  NOT NULL DEFAULT 'UNSEEN',
    attempt_id           uuid,

    first_viewed_at      timestamptz,
    time_spent_ms        int          NOT NULL DEFAULT 0,

    -- Why the engine chose this question. Random in Phase 3, real reasoning from
    -- Phase 5. Recorded from the start because "why did it show me this?" has to
    -- be answerable or the adaptive engine becomes impossible to trust or debug.
    selection_reason     jsonb        NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT session_items_unique_position UNIQUE (session_id, position),
    CONSTRAINT session_items_position_sane   CHECK (position >= 0),
    CONSTRAINT session_items_state_known     CHECK (state IN (
        'UNSEEN', 'VIEWED', 'ANSWERED', 'MARKED_FOR_REVIEW', 'SKIPPED')),
    CONSTRAINT session_items_time_sane       CHECK (time_spent_ms >= 0),
    CONSTRAINT session_items_reason_object   CHECK (jsonb_typeof(selection_reason) = 'object'),

    -- The session and the question must belong to the same preparation space.
    CONSTRAINT session_items_session_same_space_fk
        FOREIGN KEY (session_id, preparation_space_id)
        REFERENCES study_sessions (id, preparation_space_id) ON DELETE CASCADE,
    CONSTRAINT session_items_question_same_space_fk
        FOREIGN KEY (question_id, preparation_space_id)
        REFERENCES questions (id, preparation_space_id) ON DELETE RESTRICT
);

CREATE INDEX idx_session_items_session ON session_items (session_id, position);


-- =============================================================================
-- The attempt log. APPEND ONLY.
--
-- Everything derived in later phases is rebuildable from this table, which is
-- what makes replacing the spaced-repetition or selection algorithm safe. That
-- guarantee only holds if rows are never edited, so the database enforces it.
-- =============================================================================

CREATE TABLE question_attempts (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    preparation_space_id uuid         NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    question_id          uuid         NOT NULL REFERENCES questions (id) ON DELETE RESTRICT,
    question_version_id  uuid         NOT NULL REFERENCES question_versions (id) ON DELETE RESTRICT,
    -- CASCADE rather than SET NULL: setting a column to null is an UPDATE, and the
    -- trigger below refuses those. Deleting a session is only ever part of deleting
    -- the space or the account it belongs to, so taking its attempts with it is
    -- also the right meaning.
    session_id           uuid         REFERENCES study_sessions (id) ON DELETE CASCADE,

    -- Denormalised on purpose. Analytics never needs a join, which matters once
    -- this table has tens of thousands of rows; and it stays historically honest,
    -- because moving a question to another topic later must not rewrite what you
    -- were actually practising in January.
    subject_id           uuid,
    topic_id             uuid,
    difficulty           varchar(16)  NOT NULL,
    mode                 varchar(24)  NOT NULL,

    answer               jsonb        NOT NULL,
    is_correct           boolean      NOT NULL,
    -- 0.000 to 1.000. Separate from is_correct because multi-select supports
    -- partial credit: two of three right is 0.667 and is still not a right answer.
    score                numeric(4,3) NOT NULL,
    -- 1 Again, 2 Hard, 3 Good, 4 Easy. Set for flashcards now; derived from
    -- correctness and response time for other formats in Phase 6.
    grade                smallint,

    response_time_ms     int,
    confidence           smallint,
    attempt_no           int          NOT NULL DEFAULT 1,
    evaluated_by         varchar(8)   NOT NULL DEFAULT 'AUTO',

    -- Offline idempotency key, generated by the client. Phase 9 replays an outbox
    -- against this, so a retry cannot double-count an answer.
    client_attempt_id    uuid,

    created_at           timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT question_attempts_score_range CHECK (score >= 0 AND score <= 1),
    CONSTRAINT question_attempts_grade_range CHECK (grade IS NULL OR grade BETWEEN 1 AND 4),
    CONSTRAINT question_attempts_confidence_range CHECK (confidence IS NULL OR confidence BETWEEN 1 AND 4),
    CONSTRAINT question_attempts_time_sane CHECK (response_time_ms IS NULL OR response_time_ms >= 0),
    CONSTRAINT question_attempts_attempt_no_sane CHECK (attempt_no >= 1),
    CONSTRAINT question_attempts_difficulty_known CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD', 'EXPERT')),
    CONSTRAINT question_attempts_mode_known CHECK (mode IN (
        'PRACTICE', 'EXAM', 'INTERVIEW', 'FLASHCARD_REVIEW', 'DRILL')),
    CONSTRAINT question_attempts_evaluated_by_known CHECK (evaluated_by IN ('AUTO', 'SELF', 'AI')),
    CONSTRAINT question_attempts_answer_object CHECK (jsonb_typeof(answer) = 'object'),
    CONSTRAINT question_attempts_client_id_unique UNIQUE (client_attempt_id)
);

-- The three access patterns of Phases 5 to 7: a user's recent history, a topic's
-- history for mastery, and one question's history for its review state.
CREATE INDEX idx_question_attempts_user_space
    ON question_attempts (user_id, preparation_space_id, created_at DESC);

CREATE INDEX idx_question_attempts_topic
    ON question_attempts (preparation_space_id, topic_id, created_at DESC);

CREATE INDEX idx_question_attempts_question
    ON question_attempts (question_id, user_id, created_at DESC);

CREATE INDEX idx_question_attempts_session
    ON question_attempts (session_id) WHERE session_id IS NOT NULL;


-- =============================================================================
-- Immutability, enforced rather than assumed.
--
-- A convention that attempts are never edited is worth nothing the first time
-- somebody writes an UPDATE to "fix" a score. Every derived table can be dropped
-- and rebuilt from this log, and that property only holds if the log really is
-- immutable.
--
-- UPDATE is refused outright: there is no legitimate reason to change a recorded
-- answer, and correcting one means recording a new attempt.
--
-- DELETE is refused too, but with a deliberate escape hatch. Erasing an account
-- or a whole preparation space is a real operation, and a blanket ban would make
-- it impossible - the cascade would hit this trigger and fail. So a purge must
-- announce itself:
--
--     SET LOCAL patipp.purge_attempts = 'on';
--     DELETE FROM users WHERE id = ...;
--
-- The flag is transaction-scoped, so it cannot leak into later statements. The
-- point is not to make deletion hard; it is to make casual deletion impossible
-- while keeping intentional deletion straightforward.
-- =============================================================================

CREATE OR REPLACE FUNCTION reject_attempt_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND coalesce(current_setting('patipp.purge_attempts', true), 'off') = 'on' THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION
        'question_attempts is append-only; % is not permitted. Record a new attempt instead, '
        'or set patipp.purge_attempts to purge an account or space.',
        TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER question_attempts_no_update
    BEFORE UPDATE ON question_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_attempt_mutation();

CREATE TRIGGER question_attempts_no_delete
    BEFORE DELETE ON question_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_attempt_mutation();

-- session_items.attempt_id points at the log. Declared after the table exists so
-- the two can reference each other.
ALTER TABLE session_items
    ADD CONSTRAINT session_items_attempt_fk
    FOREIGN KEY (attempt_id) REFERENCES question_attempts (id) ON DELETE SET NULL;
