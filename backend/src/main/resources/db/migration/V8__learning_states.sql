-- =============================================================================
-- V8 - Spaced repetition: when each question should come back.
--
-- Derived state, like topic_mastery. The whole schedule can be dropped and
-- rebuilt by replaying question_attempts, and a test asserts that it is. That is
-- what makes replacing the scheduler later a rebuild rather than a migration that
-- guesses at history.
-- =============================================================================

CREATE TABLE learning_states (
    id                   uuid          PRIMARY KEY,
    user_id              uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    preparation_space_id uuid          NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,
    question_id          uuid          NOT NULL REFERENCES questions (id) ON DELETE CASCADE,

    phase                varchar(12)   NOT NULL DEFAULT 'NEW',

    -- Days until the chance of recall falls to about 90%. The unit the whole model
    -- is expressed in, which is why intervals equal stability at a 0.90 target.
    stability            numeric(10,4) NOT NULL DEFAULT 1.0,
    -- How hard this item is for THIS learner, 1 to 10. Seeded from the authored
    -- label and corrected by real grades, so a mislabelled question fixes itself.
    difficulty           numeric(4,2)  NOT NULL DEFAULT 5.0,

    due_at               timestamptz,
    last_reviewed_at     timestamptz,
    interval_days        numeric(10,4) NOT NULL DEFAULT 0,

    reps                 int           NOT NULL DEFAULT 0,
    lapses               int           NOT NULL DEFAULT 0,
    consecutive_correct  int           NOT NULL DEFAULT 0,
    consecutive_incorrect int          NOT NULL DEFAULT 0,
    learning_step        int           NOT NULL DEFAULT 0,

    total_attempts       int           NOT NULL DEFAULT 0,
    total_correct        int           NOT NULL DEFAULT 0,

    -- Which scheduler chose these dates. Without it, a mixture of rows written by
    -- two algorithms is indistinguishable from a bug.
    algorithm            varchar(16)   NOT NULL DEFAULT 'FSRS_V1',
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT learning_states_phase_known CHECK (phase IN (
        'NEW', 'LEARNING', 'REVIEW', 'RELEARNING', 'SUSPENDED')),
    CONSTRAINT learning_states_stability_positive CHECK (stability > 0),
    CONSTRAINT learning_states_difficulty_range CHECK (difficulty BETWEEN 1 AND 10),
    CONSTRAINT learning_states_counts_sane CHECK (
        reps >= 0 AND lapses >= 0 AND learning_step >= 0
        AND consecutive_correct >= 0 AND consecutive_incorrect >= 0
        AND total_attempts >= 0 AND total_correct >= 0
        AND total_correct <= total_attempts)
);

-- The unique key is the whole design. The same imported question can live in two
-- spaces with completely independent review histories, which is what keeps a
-- question pack shareable rather than personal.
CREATE UNIQUE INDEX uq_learning_states_item
    ON learning_states (user_id, preparation_space_id, question_id);

-- "What is due right now", which runs at the start of every session. Partial, so
-- suspended items cost nothing to skip.
CREATE INDEX idx_learning_states_due
    ON learning_states (user_id, preparation_space_id, due_at)
    WHERE phase <> 'SUSPENDED';

CREATE TRIGGER learning_states_set_updated_at
    BEFORE UPDATE ON learning_states
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE learning_states IS
    'Spaced-repetition schedule per user x space x question. Derived: rebuildable '
    'from question_attempts by replaying grades through the scheduler.';
