-- =============================================================================
-- V3 - The question bank.
--
-- Three tables, three different jobs:
--   questions          stable identity + everything you filter or sort on
--   question_versions  the mutable content, versioned so history stays honest
--   question_stats     aggregate item statistics (how hard it turned out to be)
--
-- Note what is absent: no per-user column anywhere. Review state, mastery and
-- attempt history belong to later phases and to different tables entirely. That
-- separation is what keeps a question bank exportable and shareable.
-- =============================================================================

CREATE TABLE questions (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    preparation_space_id uuid         NOT NULL,
    subject_id           uuid         NOT NULL,
    topic_id             uuid,

    -- varchar + CHECK, never a Postgres ENUM: Phase 8 adds SYSTEM_DESIGN and
    -- friends, and that must not require ALTER TYPE inside a migration.
    type                 varchar(32)  NOT NULL,
    difficulty           varchar(16)  NOT NULL,
    status               varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    source               varchar(16)  NOT NULL DEFAULT 'MANUAL',
    source_ref           text,

    -- Drives exam duration suggestions and the "was that slow?" signal the
    -- scheduler uses in Phase 6 to tell recall apart from working it out.
    estimated_seconds    int          NOT NULL DEFAULT 60,

    tags                 text[]       NOT NULL DEFAULT '{}',

    -- Set after the first version row exists, hence nullable.
    current_version_id   uuid,

    -- sha-256 over normalised type + stem. The dedupe key on import.
    content_hash         varchar(64)  NOT NULL,

    created_by           uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    archived_at          timestamptz,

    CONSTRAINT questions_type_known CHECK (type IN (
        'MCQ', 'MULTI_SELECT', 'TRUE_FALSE', 'SHORT_ANSWER', 'LONG_ANSWER',
        'FLASHCARD', 'CODING', 'DEBUGGING', 'OUTPUT_PREDICTION', 'SCENARIO', 'BEHAVIORAL')),
    CONSTRAINT questions_difficulty_known CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD', 'EXPERT')),
    CONSTRAINT questions_status_known     CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT questions_source_known     CHECK (source IN ('MANUAL', 'IMPORT', 'AI', 'SEED')),
    CONSTRAINT questions_estimate_sane    CHECK (estimated_seconds BETWEEN 5 AND 7200),
    CONSTRAINT questions_hash_hex         CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT questions_tag_count        CHECK (array_length(tags, 1) IS NULL OR array_length(tags, 1) <= 20),

    CONSTRAINT questions_space_fk
        FOREIGN KEY (preparation_space_id) REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    -- Composite FKs again: a question's subject and topic must live in the same
    -- preparation space as the question. The database refuses to hold a
    -- cross-space reference, so no service-layer slip can create one.
    CONSTRAINT questions_subject_same_space_fk
        FOREIGN KEY (subject_id, preparation_space_id)
        REFERENCES subjects (id, preparation_space_id) ON DELETE CASCADE,

    CONSTRAINT questions_topic_same_space_fk
        FOREIGN KEY (topic_id, preparation_space_id)
        REFERENCES topics (id, preparation_space_id) ON DELETE SET NULL,

    CONSTRAINT questions_id_space_unique UNIQUE (id, preparation_space_id)
);

CREATE TRIGGER questions_set_updated_at
    BEFORE UPDATE ON questions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The same question text may not be added twice to one space while it is live.
-- Archived duplicates are permitted so a deleted question can be re-imported.
CREATE UNIQUE INDEX ux_questions_space_hash
    ON questions (preparation_space_id, content_hash) WHERE archived_at IS NULL;

-- Serves the selector's candidate-pool query from Phase 5 as well as the
-- ordinary "show me active React questions" browse.
CREATE INDEX idx_questions_space_topic
    ON questions (preparation_space_id, status, topic_id) WHERE archived_at IS NULL;

CREATE INDEX idx_questions_space_difficulty
    ON questions (preparation_space_id, status, difficulty) WHERE archived_at IS NULL;

CREATE INDEX idx_questions_space_subject
    ON questions (preparation_space_id, subject_id) WHERE archived_at IS NULL;

CREATE INDEX idx_questions_tags ON questions USING gin (tags);


-- =============================================================================
-- Versions. An edit inserts a row; it never overwrites one.
-- =============================================================================

CREATE TABLE question_versions (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id  uuid        NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    version      int         NOT NULL,

    stem         text        NOT NULL,
    explanation  text,
    hints        jsonb       NOT NULL DEFAULT '[]'::jsonb,

    -- Type-specific structure: options, accepted answers, front/back, test cases.
    -- Validated against a per-type schema at the API edge before it is written,
    -- so "extensible" never degrades into "unchecked".
    payload      jsonb       NOT NULL,

    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT question_versions_unique      UNIQUE (question_id, version),
    CONSTRAINT question_versions_version_pos CHECK (version >= 1),
    CONSTRAINT question_versions_stem_len    CHECK (char_length(stem) BETWEEN 1 AND 8000),
    CONSTRAINT question_versions_payload_obj CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT question_versions_hints_array CHECK (jsonb_typeof(hints) = 'array')
);

ALTER TABLE questions
    ADD CONSTRAINT questions_current_version_fk
    FOREIGN KEY (current_version_id) REFERENCES question_versions (id) ON DELETE RESTRICT;

CREATE INDEX idx_question_versions_question ON question_versions (question_id, version DESC);

-- Full-text search over the live content. Generated and stored by Postgres, so
-- it can never drift from the text it indexes. Deliberately not mapped in the
-- entity - nothing in Java should be able to write it.
ALTER TABLE question_versions
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(stem, '') || ' ' || coalesce(explanation, ''))
    ) STORED;

CREATE INDEX idx_question_versions_search ON question_versions USING gin (search_vector);


-- =============================================================================
-- Item statistics: how hard this question turned out to be, for everyone.
--
-- This is aggregate item data, not user state, so it legitimately belongs to the
-- question. It lives in its own table to keep the write traffic of Phase 5 off
-- the questions row and to keep the content layer free of anything derived.
-- =============================================================================

CREATE TABLE question_stats (
    question_id          uuid          PRIMARY KEY REFERENCES questions (id) ON DELETE CASCADE,
    preparation_space_id uuid          NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    -- Seeded from the authored difficulty label and corrected by real responses,
    -- so a question mislabelled EASY quietly fixes itself after a few attempts.
    elo_rating           numeric(7,2)  NOT NULL DEFAULT 1200.00,
    rating_count         int           NOT NULL DEFAULT 0,

    times_served         int           NOT NULL DEFAULT 0,
    times_correct        int           NOT NULL DEFAULT 0,
    avg_response_ms      int,

    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT question_stats_rating_range CHECK (elo_rating BETWEEN 0 AND 4000),
    CONSTRAINT question_stats_counts_sane  CHECK (
        rating_count >= 0 AND times_served >= 0 AND times_correct >= 0
        AND times_correct <= times_served)
);

CREATE TRIGGER question_stats_set_updated_at
    BEFORE UPDATE ON question_stats
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_question_stats_space ON question_stats (preparation_space_id);
