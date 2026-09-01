-- =============================================================================
-- V1 - Baseline: identity, preparation spaces, curriculum.
--
-- Design rules enforced here (see docs/DATA-MODEL.md):
--   * preparation_space_id is the partition key and is carried DIRECTLY on every
--     per-space table, even where it could be reached by a join.
--   * Vocabularies are varchar + CHECK, never Postgres ENUM, so new values in
--     later phases never require ALTER TYPE.
--   * Soft delete via archived_at on anything a future attempt row may reference.
--   * Composite foreign keys make cross-space references impossible at the
--     database level, not merely unlikely at the service level.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS citext;

-- Sets updated_at on every UPDATE so the JVM clock is never the source of truth.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- =============================================================================
-- Identity
-- =============================================================================

CREATE TABLE users (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- citext: an account can never be duplicated by letter case alone.
    email           citext      NOT NULL,
    password_hash   text        NOT NULL,
    display_name    text        NOT NULL,
    timezone        text        NOT NULL DEFAULT 'UTC',
    settings        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    last_active_at  timestamptz,

    CONSTRAINT users_email_unique       UNIQUE (email),
    CONSTRAINT users_email_shape        CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'),
    CONSTRAINT users_display_name_len   CHECK (char_length(display_name) BETWEEN 1 AND 80),
    CONSTRAINT users_timezone_len       CHECK (char_length(timezone) BETWEEN 1 AND 64)
);

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- Rotating refresh tokens. The raw token is never stored - only its SHA-256.
-- replaced_by_id chains rotations so that reuse of a spent token can be detected
-- and the whole chain revoked.
CREATE TABLE refresh_tokens (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      char(64)    NOT NULL,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    replaced_by_id  uuid        REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    user_agent      text,

    CONSTRAINT refresh_tokens_hash_unique  UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_hash_hex     CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refresh_tokens_expiry_sane  CHECK (expires_at > issued_at)
);

CREATE INDEX idx_refresh_tokens_user     ON refresh_tokens (user_id, expires_at DESC);
CREATE INDEX idx_refresh_tokens_cleanup  ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;


-- =============================================================================
-- Preparation types - the extensibility mechanism.
--
-- A preparation type is a ROW, not a subclass. blueprint holds the declarative
-- configuration the engine reads: allowed question types, session modes, policy
-- keys, readiness weights, defaults. Adding a new type is an INSERT.
-- =============================================================================

CREATE TABLE preparation_types (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key         varchar(64) NOT NULL,
    name        text        NOT NULL,
    description text,
    icon        varchar(32),
    is_system   boolean     NOT NULL DEFAULT false,
    blueprint   jsonb       NOT NULL,
    created_by  uuid        REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT preparation_types_key_unique UNIQUE (key),
    CONSTRAINT preparation_types_key_shape  CHECK (key ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT preparation_types_name_len   CHECK (char_length(name) BETWEEN 1 AND 120),
    -- A system type is owned by nobody; a user-defined type must have an owner.
    CONSTRAINT preparation_types_ownership  CHECK (
        (is_system AND created_by IS NULL) OR (NOT is_system AND created_by IS NOT NULL)
    ),
    CONSTRAINT preparation_types_blueprint_object CHECK (jsonb_typeof(blueprint) = 'object')
);

CREATE TRIGGER preparation_types_set_updated_at
    BEFORE UPDATE ON preparation_types
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX idx_preparation_types_owner ON preparation_types (created_by) WHERE created_by IS NOT NULL;


-- =============================================================================
-- Preparation spaces - the partition key of the entire system.
-- =============================================================================

CREATE TABLE preparation_spaces (
    id                  uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- RESTRICT: a type in use may not be deleted out from under a space.
    preparation_type_id uuid         NOT NULL REFERENCES preparation_types (id) ON DELETE RESTRICT,
    name                text         NOT NULL,
    description         text,
    status              varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    target_date         date,
    target_score        smallint,
    -- Shallow-merged OVER preparation_types.blueprint. This is how a single
    -- space gets its own difficulty rules without needing its own type.
    config              jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    archived_at         timestamptz,

    CONSTRAINT preparation_spaces_name_len     CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT preparation_spaces_status_known CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'ARCHIVED')),
    CONSTRAINT preparation_spaces_target_score CHECK (target_score IS NULL OR target_score BETWEEN 0 AND 100),
    CONSTRAINT preparation_spaces_config_object CHECK (jsonb_typeof(config) = 'object'),
    -- Needed as the target of the composite foreign keys below.
    CONSTRAINT preparation_spaces_id_user_unique UNIQUE (id, user_id)
);

CREATE TRIGGER preparation_spaces_set_updated_at
    BEFORE UPDATE ON preparation_spaces
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- One live space per name per user; archived spaces may reuse a name.
CREATE UNIQUE INDEX ux_preparation_spaces_user_name
    ON preparation_spaces (user_id, lower(name)) WHERE archived_at IS NULL;

CREATE INDEX idx_preparation_spaces_user
    ON preparation_spaces (user_id, status) WHERE archived_at IS NULL;


-- =============================================================================
-- Curriculum: subjects, and topics as a self-referencing tree.
-- =============================================================================

CREATE TABLE subjects (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    preparation_space_id uuid         NOT NULL REFERENCES preparation_spaces (id) ON DELETE CASCADE,
    name                 text         NOT NULL,
    description          text,
    color                varchar(16),
    position             smallint     NOT NULL DEFAULT 0,
    -- Exam blueprint weighting: if React is 30% of the paper, readiness must
    -- reflect that rather than treating every subject equally.
    weight               numeric(6,2) NOT NULL DEFAULT 1.00,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    archived_at          timestamptz,

    CONSTRAINT subjects_name_len    CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT subjects_weight_sane CHECK (weight >= 0 AND weight <= 1000),
    CONSTRAINT subjects_position_ok CHECK (position >= 0),
    CONSTRAINT subjects_color_shape CHECK (color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$'),
    -- Target of the composite foreign key from topics.
    CONSTRAINT subjects_id_space_unique UNIQUE (id, preparation_space_id)
);

CREATE TRIGGER subjects_set_updated_at
    BEFORE UPDATE ON subjects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE UNIQUE INDEX ux_subjects_space_name
    ON subjects (preparation_space_id, lower(name)) WHERE archived_at IS NULL;

CREATE INDEX idx_subjects_space_position
    ON subjects (preparation_space_id, position) WHERE archived_at IS NULL;


CREATE TABLE topics (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    preparation_space_id uuid         NOT NULL,
    subject_id           uuid         NOT NULL,
    parent_topic_id      uuid,
    name                 text         NOT NULL,
    description          text,
    position             smallint     NOT NULL DEFAULT 0,
    weight               numeric(6,2) NOT NULL DEFAULT 1.00,
    -- 0 = topic, 1 = subtopic, 2 = sub-subtopic. Capped because deeper trees are
    -- unusable in a UI and make mastery roll-ups meaningless.
    depth                smallint     NOT NULL DEFAULT 0,
    -- Denormalised materialised path ("React / Hooks / useEffect") for cheap
    -- breadcrumbs and subtree queries.
    path                 text         NOT NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    archived_at          timestamptz,

    CONSTRAINT topics_name_len    CHECK (char_length(name) BETWEEN 1 AND 120),
    CONSTRAINT topics_depth_range CHECK (depth BETWEEN 0 AND 2),
    CONSTRAINT topics_weight_sane CHECK (weight >= 0 AND weight <= 1000),
    CONSTRAINT topics_position_ok CHECK (position >= 0),
    CONSTRAINT topics_not_own_parent CHECK (parent_topic_id IS NULL OR parent_topic_id <> id),

    CONSTRAINT topics_space_fk
        FOREIGN KEY (preparation_space_id) REFERENCES preparation_spaces (id) ON DELETE CASCADE,

    -- Composite FKs: a topic's subject and its parent MUST live in the same
    -- preparation space. Cross-space references are rejected by the database,
    -- so a service-layer bug cannot silently produce them.
    CONSTRAINT topics_subject_same_space_fk
        FOREIGN KEY (subject_id, preparation_space_id)
        REFERENCES subjects (id, preparation_space_id) ON DELETE CASCADE,

    CONSTRAINT topics_parent_same_space_fk
        FOREIGN KEY (parent_topic_id, preparation_space_id)
        REFERENCES topics (id, preparation_space_id) ON DELETE CASCADE,

    CONSTRAINT topics_id_space_unique UNIQUE (id, preparation_space_id)
);

CREATE TRIGGER topics_set_updated_at
    BEFORE UPDATE ON topics
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Sibling names are unique. The all-zero UUID stands in for "no parent" so that
-- root topics of the same subject are compared against each other.
CREATE UNIQUE INDEX ux_topics_sibling_name
    ON topics (subject_id, COALESCE(parent_topic_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name))
    WHERE archived_at IS NULL;

CREATE INDEX idx_topics_space_subject
    ON topics (preparation_space_id, subject_id, position) WHERE archived_at IS NULL;

CREATE INDEX idx_topics_parent
    ON topics (parent_topic_id) WHERE parent_topic_id IS NOT NULL AND archived_at IS NULL;
