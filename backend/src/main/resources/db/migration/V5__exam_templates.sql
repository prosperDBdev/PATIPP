-- =============================================================================
-- V5 - Saved exam setups.
--
-- Note how little this migration contains. Exam mode reuses study_sessions,
-- session_items and question_attempts exactly as they are: an exam is a session
-- with a deadline, deferred feedback and weighted selection, so the only thing
-- genuinely new is remembering a setup you want to sit again.
--
-- session_items.state already permits MARKED_FOR_REVIEW and SKIPPED - they were
-- declared in V4 precisely so exam navigation would not need a migration that
-- widens a check constraint on a table holding real history by then.
-- =============================================================================

CREATE TABLE exam_templates (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    preparation_space_id uuid         NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    name                 text         NOT NULL,

    -- Length, duration and any subject, topic, type or difficulty filters.
    -- The same shape a start-session request takes, so sitting a template is
    -- literally replaying that request.
    config               jsonb        NOT NULL DEFAULT '{}'::jsonb,

    -- Counts sittings, so the most-used setup can be offered first.
    times_used           int          NOT NULL DEFAULT 0,
    last_used_at         timestamptz,

    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    archived_at          timestamptz,

    CONSTRAINT exam_templates_name_len    CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT exam_templates_config_obj  CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT exam_templates_uses_sane   CHECK (times_used >= 0)
);

CREATE TRIGGER exam_templates_set_updated_at
    BEFORE UPDATE ON exam_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE UNIQUE INDEX ux_exam_templates_space_name
    ON exam_templates (preparation_space_id, lower(name)) WHERE archived_at IS NULL;

CREATE INDEX idx_exam_templates_space
    ON exam_templates (preparation_space_id, last_used_at DESC NULLS LAST)
    WHERE archived_at IS NULL;
