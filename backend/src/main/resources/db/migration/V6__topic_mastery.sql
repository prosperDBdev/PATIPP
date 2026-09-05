-- =============================================================================
-- V6 - Derived learner state: what the adaptive engine knows about you.
--
-- Layer three of the three-layer model. Everything here is DERIVED: it can be
-- dropped and rebuilt from question_attempts at any time, and there is a test
-- that proves it. Nothing in this file is a source of truth, which is why it is
-- safe to change the algorithm that fills it.
-- =============================================================================

CREATE TABLE topic_mastery (
    id                   uuid          PRIMARY KEY,
    user_id              uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    preparation_space_id uuid          NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    -- No foreign key to subjects or topics on purpose. A row here describes what
    -- you did in January; archiving a topic in March must not delete the evidence,
    -- and the attempt log this is derived from denormalises the same two columns
    -- for exactly the same reason.
    subject_id           uuid          NOT NULL,
    -- Null means "this subject's questions that have no topic". Untagged questions
    -- are practised like any other, so they need somewhere to accumulate; without
    -- this bucket they would be invisible to weakness detection.
    topic_id             uuid,

    -- Elo, learner side. Seeded at 1200 and moved by every answer.
    ability              numeric(7,2)  NOT NULL DEFAULT 1200.00,

    attempts             int           NOT NULL DEFAULT 0,
    correct              int           NOT NULL DEFAULT 0,

    -- Recency-weighted counts, halving every 14 days. Kept as a pair of running
    -- sums rather than as a percentage so the decay can be applied incrementally:
    -- on each attempt both are multiplied by 0.5^(elapsed/halfLife) and then the
    -- new answer is added. That is exact, bounded, and needs no history rescan.
    -- The percentage itself is derived, because a stored one would be a second
    -- source of truth for the same fact.
    decayed_attempts     numeric(12,6) NOT NULL DEFAULT 0,
    decayed_correct      numeric(12,6) NOT NULL DEFAULT 0,
    decayed_at           timestamptz,

    -- Distinct questions ever attempted here. Coverage is computed against the
    -- live question count at read time rather than stored: adding ten questions
    -- to a topic genuinely reduces your coverage of it, and a stored fraction
    -- would quietly claim otherwise.
    questions_seen       int           NOT NULL DEFAULT 0,

    consecutive_correct  int           NOT NULL DEFAULT 0,
    consecutive_wrong    int           NOT NULL DEFAULT 0,

    total_response_ms    bigint        NOT NULL DEFAULT 0,
    timed_attempts       int           NOT NULL DEFAULT 0,

    mastery_level        varchar(16)   NOT NULL DEFAULT 'UNTOUCHED',

    last_practiced_at    timestamptz,
    last_incorrect_at    timestamptz,

    -- Which version of the engine produced these numbers. Without it, a mixture of
    -- rows written by two algorithms is indistinguishable from a bug.
    algorithm            varchar(24)   NOT NULL DEFAULT 'ELO_V1',
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT topic_mastery_ability_range CHECK (ability BETWEEN 0 AND 4000),
    CONSTRAINT topic_mastery_counts_sane CHECK (
        attempts >= 0 AND correct >= 0 AND correct <= attempts
        AND questions_seen >= 0 AND questions_seen <= attempts
        AND consecutive_correct >= 0 AND consecutive_wrong >= 0
        AND timed_attempts >= 0 AND total_response_ms >= 0),
    CONSTRAINT topic_mastery_decayed_sane CHECK (
        decayed_attempts >= 0 AND decayed_correct >= 0
        AND decayed_correct <= decayed_attempts + 0.000001),
    CONSTRAINT topic_mastery_level_known CHECK (mastery_level IN (
        'UNTOUCHED', 'UNASSESSED', 'WEAK', 'DEVELOPING', 'PROFICIENT', 'STRONG'))
);

-- One row per bucket. Two partial indexes rather than one constraint, because in
-- Postgres a UNIQUE over a nullable column does not treat two nulls as equal, so
-- the untagged bucket would silently accept duplicates.
CREATE UNIQUE INDEX uq_topic_mastery_topic
    ON topic_mastery (user_id, preparation_space_id, topic_id)
    WHERE topic_id IS NOT NULL;

CREATE UNIQUE INDEX uq_topic_mastery_untagged
    ON topic_mastery (user_id, preparation_space_id, subject_id)
    WHERE topic_id IS NULL;

-- The query that runs at the start of every session: the whole learner model for
-- one space, in one index scan.
CREATE INDEX idx_topic_mastery_learner
    ON topic_mastery (user_id, preparation_space_id);

CREATE TRIGGER topic_mastery_set_updated_at
    BEFORE UPDATE ON topic_mastery
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- No new indexes on question_attempts: V4 already added the three access patterns
-- this phase needs (a user's recent history, a topic's history, one question's
-- history), anticipating exactly this work.
