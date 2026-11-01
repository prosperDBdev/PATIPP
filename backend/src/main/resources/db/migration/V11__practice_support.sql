-- In-progress assistance is copied into the immutable answer when the learner commits.
ALTER TABLE session_items ADD COLUMN hints_revealed integer NOT NULL DEFAULT 0
    CHECK (hints_revealed >= 0);
ALTER TABLE session_items ADD COLUMN solution_revealed boolean NOT NULL DEFAULT false;
