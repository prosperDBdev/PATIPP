-- =============================================================================
-- V10 - Federated sign-in: an account can have more than one way in.
--
-- Google is the first provider and the shape here assumes it will not be the
-- last. A provider is a ROW, not a column and not a subclass - the same rule the
-- rest of this schema follows for preparation types. Adding GitHub later is one
-- value in a CHECK, never an ALTER on users.
--
-- The smaller-looking alternative, auth_provider and external_id columns on
-- users, breaks as soon as an account has two ways in. A learner who registered
-- with a password and later signs in with Google has both, and one column cannot
-- say so: it must either lie about the password or lose the Google link.
-- =============================================================================

-- A federated account has no password. Every existing row keeps its hash; only
-- the obligation to have one is lifted.
--
-- The invariant "an account must be reachable" - a password or at least one
-- identity - is deliberately NOT a constraint here, because it spans two tables
-- and would need a trigger. It is enforced in AuthService, which creates the user
-- and its identity in one transaction, and asserted by a test. A row with
-- neither is unreachable rather than dangerous: password login rejects a null
-- hash and identity lookup finds nothing.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE user_identities (
    id            uuid         PRIMARY KEY,
    user_id       uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- varchar + CHECK, never a Postgres ENUM: adding a provider then edits a
    -- constraint instead of rewriting a type.
    provider      varchar(16)  NOT NULL,

    -- The provider's own immutable subject ('sub' in an ID token). Deliberately
    -- NOT the email. Google's own guidance is that an address can be changed by
    -- the user, and keying on a mutable address is how accounts get taken over.
    external_id   varchar(255) NOT NULL,

    -- The address the provider asserted, stored for auditing and for showing the
    -- learner which Google account is linked. Never used to resolve an identity.
    email         text         NOT NULL,

    created_at    timestamptz  NOT NULL DEFAULT now(),
    last_login_at timestamptz,

    CONSTRAINT user_identities_provider_known   CHECK (provider IN ('GOOGLE')),
    CONSTRAINT user_identities_email_lowercase  CHECK (email = lower(email)),
    CONSTRAINT user_identities_email_shape      CHECK (
        email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'),

    -- One PATIPP account per provider subject. This is the constraint that stops
    -- a single Google account from owning two learners' data.
    CONSTRAINT user_identities_subject_unique   UNIQUE (provider, external_id),

    -- One identity per provider per user, so "sign in with Google" resolves to
    -- exactly one row. Its index also serves lookups by user_id, which is why
    -- there is no separate index on that column.
    CONSTRAINT user_identities_one_per_user     UNIQUE (user_id, provider)
);
