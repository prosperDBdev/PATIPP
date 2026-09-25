-- =============================================================================
-- V9 - Readiness snapshots and daily activity.
--
-- Both derived, both rebuildable from the attempt log. A snapshot is kept rather
-- than recomputed on demand for one reason: a score that cannot say how it changed
-- since yesterday cannot be acted on, and "since yesterday" needs yesterday to
-- have been written down.
-- =============================================================================

CREATE TABLE readiness_snapshots (
    id                   uuid          PRIMARY KEY,
    user_id              uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    preparation_space_id uuid          NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    -- One per day per space. A readiness figure that moved four times before lunch
    -- is noise; the useful question is how today compares with yesterday.
    captured_on          date          NOT NULL,

    score                numeric(5,2)  NOT NULL,
    -- Before the confidence factor. Kept because the gap between the two IS the
    -- explanation for an early score that looks unfairly low.
    raw_score            numeric(5,2)  NOT NULL,
    confidence           numeric(4,3)  NOT NULL,
    confidence_band      varchar(12)   NOT NULL,

    -- The six components, the weights applied, the change since the last snapshot,
    -- and the sentences worth reading. Stored rather than recomputed so a score
    -- recorded in March can still account for itself in June, after the weights
    -- have changed.
    components           jsonb         NOT NULL DEFAULT '{}'::jsonb,
    weights              jsonb         NOT NULL DEFAULT '{}'::jsonb,
    deltas               jsonb         NOT NULL DEFAULT '{}'::jsonb,
    drivers              jsonb         NOT NULL DEFAULT '[]'::jsonb,
    -- The single most useful output of the whole module: what to do next.
    biggest_lever        jsonb         NOT NULL DEFAULT '{}'::jsonb,

    model_version        varchar(16)   NOT NULL,
    created_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT readiness_score_range CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT readiness_raw_range CHECK (raw_score BETWEEN 0 AND 100),
    CONSTRAINT readiness_confidence_range CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT readiness_band_known CHECK (confidence_band IN (
        'CALIBRATING', 'LOW', 'MODERATE', 'HIGH')),
    CONSTRAINT readiness_components_object CHECK (jsonb_typeof(components) = 'object'),
    CONSTRAINT readiness_weights_object CHECK (jsonb_typeof(weights) = 'object'),
    CONSTRAINT readiness_deltas_object CHECK (jsonb_typeof(deltas) = 'object'),
    CONSTRAINT readiness_drivers_array CHECK (jsonb_typeof(drivers) = 'array'),
    CONSTRAINT readiness_lever_object CHECK (jsonb_typeof(biggest_lever) = 'object')
);

CREATE UNIQUE INDEX uq_readiness_snapshot_day
    ON readiness_snapshots (user_id, preparation_space_id, captured_on);

-- The history chart: one space, newest first.
CREATE INDEX idx_readiness_history
    ON readiness_snapshots (user_id, preparation_space_id, captured_on DESC);


-- =============================================================================
-- Daily activity: streaks and the heatmap, without scanning the attempt log.
-- =============================================================================

CREATE TABLE daily_activity (
    id                   uuid          PRIMARY KEY,
    user_id              uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Nullable on purpose: a NULL space is the cross-space roll-up, so "did I study
    -- today" can be answered without summing every space the learner has.
    preparation_space_id uuid          REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    activity_date        date          NOT NULL,
    questions_answered   int           NOT NULL DEFAULT 0,
    correct              int           NOT NULL DEFAULT 0,
    study_time_ms        bigint        NOT NULL DEFAULT 0,
    sessions_completed   int           NOT NULL DEFAULT 0,

    updated_at           timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT daily_activity_counts_sane CHECK (
        questions_answered >= 0 AND correct >= 0 AND correct <= questions_answered
        AND study_time_ms >= 0 AND sessions_completed >= 0)
);

-- Two partial indexes rather than one constraint, because a UNIQUE over a nullable
-- column does not treat two NULLs as equal, so the cross-space roll-up would
-- silently accept duplicate rows for the same day.
CREATE UNIQUE INDEX uq_daily_activity_space
    ON daily_activity (user_id, preparation_space_id, activity_date)
    WHERE preparation_space_id IS NOT NULL;

CREATE UNIQUE INDEX uq_daily_activity_global
    ON daily_activity (user_id, activity_date)
    WHERE preparation_space_id IS NULL;

CREATE INDEX idx_daily_activity_recent
    ON daily_activity (user_id, activity_date DESC);

CREATE TRIGGER daily_activity_set_updated_at
    BEFORE UPDATE ON daily_activity
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE daily_activity IS
    'Per-day counts for streaks and the heatmap. Derived: rebuildable from '
    'question_attempts by grouping on the learner''s own date.';
