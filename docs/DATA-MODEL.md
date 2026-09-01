# PATIPP — Data Model Proposal

PostgreSQL 16. All ids are `uuid` (v7, time-ordered — index-friendly and sortable).
All timestamps are `timestamptz`. Migrations via Flyway; Hibernate runs `ddl-auto: validate`
and never generates schema.

---

## 1. The shape of the whole thing

```
users
  |
  +-- preparation_spaces ------------------ preparation_types (blueprint jsonb)
        |
        +-- subjects
        |     +-- topics (self-referencing tree -> subtopics)
        |
        +-- questions ---- question_versions      (content; versioned, soft-deleted)
        |     +-- question_stats                  (item statistics: Elo, p-value)
        |
        +-- study_materials ---- material_links ---- material_chunks
        |
        +-- study_sessions ---- session_items
        |     +-- interview_turns                  (INTERVIEW mode only)
        |
        +-- question_attempts                      (APPEND-ONLY event log)
        |
        +-- learning_states                        (derived: per user x space x question)
        +-- topic_mastery                          (derived: per user x space x topic)
        +-- readiness_snapshots                    (derived: dated, with breakdown)
        +-- daily_activity                         (derived: streaks, heatmap)
        +-- study_plans                            (derived: today's recommendation)
```

### The three-layer separation that makes this work

| Layer | Tables | Property |
|---|---|---|
| **Content** | `questions`, `question_versions`, `subjects`, `topics`, `study_materials` | Shareable, importable, exportable. Contains **no user performance data whatsoever.** |
| **Events** | `question_attempts`, `study_sessions`, `session_items`, `interview_turns` | Append-only, immutable. The source of truth for everything derived. |
| **Derived** | `learning_states`, `topic_mastery`, `readiness_snapshots`, `daily_activity`, `question_stats` | Can be **fully rebuilt** from the event log at any time. |

That last property is the safety net. When you improve the spaced-repetition algorithm in
month four, you truncate `learning_states` and replay the attempt log. Nothing is lost.
If review state lived on the question row, that would be impossible.

---

## 2. Identity and access

### `users`
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `email` | citext UNIQUE NOT NULL | citext so case never causes a duplicate account |
| `password_hash` | text NOT NULL | BCrypt, strength 12 |
| `display_name` | text | |
| `timezone` | text NOT NULL DEFAULT 'UTC' | streaks and "today" are timezone-sensitive |
| `settings` | jsonb NOT NULL DEFAULT '{}' | theme, shortcuts, daily goal, notification prefs |
| `created_at`, `updated_at`, `last_active_at` | timestamptz | |

### `refresh_tokens`
`id`, `user_id` FK, `token_hash` (never the raw token), `expires_at`, `revoked_at`,
`replaced_by_id`, `user_agent`, `created_at`.
Rotation: each refresh issues a new row and sets `replaced_by_id` on the old one. Reuse of
a revoked token revokes the whole chain — standard refresh-token-reuse detection.

---

## 3. Preparation spaces (the partition key of the entire system)

### `preparation_types`
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `key` | varchar(64) UNIQUE | `ACADEMIC_EXAM`, `INTERVIEW`, `CERTIFICATION`, `CODING_TEST`, `GENERAL_TEST`, `CUSTOM` |
| `name`, `description`, `icon` | text | |
| `is_system` | boolean | system types are seeded and not user-deletable |
| `blueprint` | jsonb NOT NULL | see ARCHITECTURE.md section 2 |
| `created_by` | uuid NULL | non-null for user-defined types |

**A new preparation type is an INSERT.** That is the whole extensibility story at the data
layer.

### `preparation_spaces`
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id` | uuid FK NOT NULL | |
| `preparation_type_id` | uuid FK NOT NULL | |
| `name` | text NOT NULL | "NIIT Semester 2 Exams" |
| `description` | text | |
| `status` | varchar(16) | `ACTIVE` / `PAUSED` / `COMPLETED` / `ARCHIVED` |
| `target_date` | date NULL | the exam / interview date |
| `target_score` | smallint NULL | 85 |
| `config` | jsonb NOT NULL DEFAULT '{}' | **overrides** merged over the type blueprint |
| `created_at`, `updated_at`, `archived_at` | timestamptz | |

- `UNIQUE (user_id, lower(name)) WHERE archived_at IS NULL`
- `config` is a shallow merge over `preparation_types.blueprint`. This is how a space gets
  its own difficulty rules and readiness weights without a new type — exactly the
  "eventually allow preparation spaces to have their own difficulty rules" requirement.

> **Rule:** every table below that holds per-space data carries `preparation_space_id`
> **directly**, even when it could be reached through a join. It is the partition key, it
> appears in every index, and it is a mandatory parameter on every repository method.

---

## 4. Curriculum

### `subjects`
`id`, `preparation_space_id` FK, `name`, `description`, `color`, `position` smallint,
`weight` numeric(5,2) DEFAULT 1.0, `created_at`, `archived_at`.
`UNIQUE (preparation_space_id, lower(name)) WHERE archived_at IS NULL`

`weight` is the exam blueprint weighting — if React is 30% of the paper, readiness must
reflect that rather than treating all subjects equally.

### `topics`
`id`, `preparation_space_id` FK, `subject_id` FK, `parent_topic_id` FK NULL (self-reference),
`name`, `description`, `position`, `weight`, `path` ltree-or-text, `created_at`, `archived_at`.

Topics are a **self-referencing tree**, so "subtopic" needs no second table:
`React -> Hooks -> useEffect dependencies`. Depth is capped at 3 in the application layer
(deeper trees are unusable in a UI and make mastery rollups meaningless). `path` is a
denormalised materialised path for cheap subtree queries and breadcrumbs.

Why keep `subjects` separate from a depth-0 topic? Because subjects are the reporting grain
you actually asked for ("HTML: 91%, CSS: 87%") and they carry the exam weighting. Merging
them would make every analytics query filter on depth.

---

## 5. The question bank

### `questions` — the stable identity and everything you filter on
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `preparation_space_id` | uuid FK NOT NULL | |
| `subject_id` | uuid FK NOT NULL | |
| `topic_id` | uuid FK NULL | |
| `type` | varchar(32) NOT NULL | `MCQ`, `MULTI_SELECT`, `TRUE_FALSE`, `SHORT_ANSWER`, `LONG_ANSWER`, `FLASHCARD`, `CODING`, `DEBUGGING`, `OUTPUT_PREDICTION`, `SCENARIO`, `BEHAVIORAL` |
| `difficulty` | varchar(16) NOT NULL | `EASY` / `MEDIUM` / `HARD` / `EXPERT` (authored estimate) |
| `status` | varchar(16) NOT NULL | `DRAFT` / `ACTIVE` / `ARCHIVED` |
| `source` | varchar(16) NOT NULL | `MANUAL` / `IMPORT` / `AI` / `SEED` |
| `source_ref` | text NULL | file name, URL, textbook page |
| `estimated_seconds` | int | drives exam duration suggestions and pacing feedback |
| `tags` | text[] | GIN-indexed |
| `current_version_id` | uuid FK | -> `question_versions` |
| `content_hash` | char(64) | sha-256 of normalised stem; **dedupe key on import** |
| `created_by`, `created_at`, `updated_at`, `archived_at` | | |

Indexes: `(preparation_space_id, status, topic_id)`, `(preparation_space_id, status, difficulty)`,
GIN on `tags`, GIN on a `search_vector` tsvector, `UNIQUE (preparation_space_id, content_hash) WHERE archived_at IS NULL`.

`type` and `difficulty` are `varchar` with a check constraint, **not** a Postgres `ENUM` —
adding `SYSTEM_DESIGN` later must not require `ALTER TYPE` inside a migration.

### `question_versions` — the mutable content, versioned
`id`, `question_id` FK, `version` int, `stem` text NOT NULL, `explanation` text,
`hints` jsonb, `payload` jsonb NOT NULL, `created_by`, `created_at`.
`UNIQUE (question_id, version)`

Editing a question inserts a new version and repoints `current_version_id`. Every attempt
records the exact `question_version_id` it was answered against, so a typo fix in March
never silently rewrites what you were scored on in January.

### `question_stats` — item statistics (aggregate, not per-user)
`question_id` PK, `preparation_space_id`, `elo_rating` numeric DEFAULT 1200,
`rating_count` int, `times_served`, `times_correct`, `avg_response_ms`,
`discrimination` numeric NULL, `updated_at`.

This is the *item* side of the Elo model in ADAPTIVE-ENGINE.md. It is aggregate item
difficulty, not user state, so it is legitimately question-scoped — but it lives in its own
table to keep writes off `questions` and to keep the content layer pure.

### Payload shapes by type

`payload` is validated against a per-type JSON Schema at the API edge **before** it is ever
written. One table, one column, a dozen shapes, no DDL to add the thirteenth.

```jsonc
// MCQ
{ "options": [ {"id":"a","text":"...","correct":true}, ... ], "shuffle": true }

// MULTI_SELECT
{ "options": [...], "minSelect": 1, "maxSelect": 3, "partialCredit": true }

// TRUE_FALSE
{ "answer": true }

// SHORT_ANSWER
{ "acceptedAnswers": ["event loop","the event loop"],
  "matchMode": "NORMALIZED",          // EXACT | NORMALIZED | REGEX | KEYWORDS
  "requiredKeywords": ["queue","stack"], "caseSensitive": false }

// LONG_ANSWER / SCENARIO / BEHAVIORAL
{ "modelAnswer": "...", "keyPoints": ["...","..."],
  "rubric": [ {"criterion":"correctness","weight":0.4,"descriptor":"..."}, ... ],
  "followUps": ["Why is constructor injection preferred?"] }

// FLASHCARD
{ "front": "What is dependency injection?", "back": "...", "mnemonic": "..." }

// CODING
{ "language": "javascript", "starterCode": "...", "referenceSolution": "...",
  "testCases": [ {"input":"[1,2]","expected":"3","hidden":false} ],
  "constraints": ["O(n) time"], "timeLimitMs": 2000 }

// DEBUGGING
{ "language": "java", "brokenCode": "...", "fixedCode": "...",
  "bugSummary": "off-by-one", "testCases": [...] }

// OUTPUT_PREDICTION
{ "language": "javascript", "code": "...", "expectedOutput": "1\n3\n2", "trim": true }
```

**A flashcard is a question type, not a separate subsystem.** It is stored in `questions`,
reviewed through a session, logged in `question_attempts`, and scheduled by the same
`learning_states` row as everything else. This unification is why the Again/Hard/Good/Easy
grades feed the same engine that drives your exam readiness — which is what you asked for
in requirement 15, and it comes for free rather than as an integration.

---

## 6. Sessions — one engine for every mode

### `study_sessions`
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id`, `preparation_space_id` | uuid FK | |
| `mode` | varchar(24) | `PRACTICE` / `EXAM` / `INTERVIEW` / `FLASHCARD_REVIEW` / `DRILL` |
| `status` | varchar(16) | `IN_PROGRESS` / `SUBMITTED` / `ABANDONED` / `EXPIRED` |
| `config` | jsonb | length, duration, topic filter, difficulty filter, type filter, immediate-feedback flag, blueprint |
| `started_at` | timestamptz | **server-set** |
| `deadline_at` | timestamptz NULL | **server-computed**; submissions after this are rejected |
| `submitted_at`, `active_ms` | | `active_ms` excludes idle gaps |
| `total_items`, `answered_count`, `correct_count` | int | |
| `score` | numeric(5,2) NULL | |
| `score_breakdown` | jsonb NULL | per subject, per topic, per difficulty, per type |
| `selection_trace` | jsonb NULL | why the adaptive engine chose this set |

### `session_items`
`id`, `session_id` FK, `position` int, `question_id`, `question_version_id`,
`state` (`UNSEEN`/`VIEWED`/`ANSWERED`/`MARKED_FOR_REVIEW`/`SKIPPED`), `attempt_id` FK NULL,
`first_viewed_at`, `time_spent_ms`, `selection_reason` jsonb.
`UNIQUE (session_id, position)`

`selection_reason` — e.g. `{"reason":"DUE_REVIEW","dueSince":"P3D","weaknessScore":0.71}` —
is the debugging tool that makes the adaptive engine tractable. Without it, "why did it
show me this?" is unanswerable and you will not trust the engine.

### `interview_turns` (INTERVIEW mode only)
`id`, `session_id` FK, `parent_turn_id` FK NULL, `question_id` FK NULL, `sequence` int,
`prompt` text, `answer` text, `evaluation` jsonb, `overall_score` numeric,
`evaluated_by` (`RUBRIC`/`AI`/`SELF`), `asked_at`, `answered_at`.

`parent_turn_id` gives you the follow-up chain from requirement 9. `question_id` is nullable
because an AI-generated follow-up is not a bank question — but it still produces a real
attempt row so it counts toward topic mastery.

---

## 7. The event log

### `question_attempts` — APPEND ONLY, NEVER UPDATED
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `user_id`, `preparation_space_id` | uuid FK | |
| `question_id`, `question_version_id` | uuid FK | pinned version |
| `session_id` | uuid FK NULL | null for one-off reviews |
| `subject_id`, `topic_id`, `difficulty` | denormalised | see note below |
| `mode` | varchar(24) | copied from the session |
| `answer` | jsonb | the raw submission, whatever its shape |
| `is_correct` | boolean | |
| `score` | numeric(4,3) | 0.000–1.000, supports partial credit |
| `grade` | smallint NULL | 1–4 (Again/Hard/Good/Easy) for flashcard-style reviews |
| `response_time_ms` | int | |
| `confidence` | smallint NULL | optional self-report, 1–4 |
| `attempt_no` | int | 1st, 2nd, 3rd time seeing this question |
| `evaluated_by` | varchar(8) | `AUTO` / `SELF` / `AI` |
| `client_attempt_id` | uuid UNIQUE NULL | **offline idempotency key** |
| `created_at` | timestamptz | |

Indexes: `(user_id, preparation_space_id, created_at DESC)`,
`(preparation_space_id, topic_id, created_at DESC)`, `(question_id, user_id)`.

**Why denormalise subject/topic/difficulty onto the attempt?** Two reasons. Analytics
queries never need a join, which matters once this table has 50k rows. And it is
historically honest: if you later move a question from "React" to "React Native", your
January results still say what you were actually practising in January.

**`client_attempt_id` is the entire offline sync story.** The client generates a UUID per
attempt; the server upserts on conflict-do-nothing. Replaying the outbox is safe, so the
sync client can be dumb and retry forever.

---

## 8. Derived state (rebuildable from the log)

### `learning_states` — spaced repetition, per user x space x question
`id`, `user_id`, `preparation_space_id`, `question_id`,
`state` (`NEW`/`LEARNING`/`REVIEW`/`RELEARNING`), `stability` numeric, `difficulty` numeric,
`due_at` timestamptz, `last_reviewed_at`, `interval_days` numeric, `reps` int, `lapses` int,
`consecutive_correct` int, `consecutive_incorrect` int, `total_attempts`, `total_correct`,
`avg_response_ms`, `familiarity` numeric, `algorithm` varchar(16), `updated_at`.

`UNIQUE (user_id, preparation_space_id, question_id)`
Index: `(user_id, preparation_space_id, due_at) WHERE state <> 'SUSPENDED'` — this single
index serves the "what is due right now" query, which runs on every session start.

> **The unique key is the whole design.** The same imported question can live in two spaces
> with completely independent review histories. Had this state been columns on `questions`,
> question packs would be personal, un-shareable, and un-importable.

### `topic_mastery` — per user x space x topic
`user_id`, `preparation_space_id`, `topic_id`, `ability` numeric (Elo, learner side),
`accuracy` numeric, `decayed_accuracy` numeric (recency-weighted), `attempts`, `correct`,
`avg_response_ms`, `coverage` numeric (fraction of the topic's questions ever seen),
`mastery_level` varchar (`UNTOUCHED`/`WEAK`/`DEVELOPING`/`PROFICIENT`/`STRONG`),
`last_practiced_at`, `updated_at`. `UNIQUE (user_id, preparation_space_id, topic_id)`

Updated incrementally on each attempt (cheap), fully recomputable on demand (correct).

### `readiness_snapshots`
`id`, `user_id`, `preparation_space_id`, `captured_on` date, `score` numeric(5,2),
`components` jsonb, `deltas` jsonb, `model_version` varchar, `created_at`.
`UNIQUE (user_id, preparation_space_id, captured_on)`

```jsonc
{ "components": { "coverage": 68, "accuracy": 81, "depth": 64,
                  "retention": 77, "consistency": 90, "mock": 72 },
  "weights":    { "coverage": 0.15, "accuracy": 0.30, ... },
  "deltas":     { "total": +2.1, "retention": -3.0, "accuracy": +1.4 },
  "drivers":    ["3 React Native topics overdue", "Mock exam +7% vs last"] }
```

Storing the breakdown, the weights and the drivers is what makes requirement 13's
"transparent enough that the user can understand why their readiness score changed"
actually true. A bare number cannot explain itself.

### `daily_activity`
`user_id`, `preparation_space_id` NULL, `activity_date` date, `questions_answered`,
`correct`, `study_time_ms`, `sessions_completed`.
`UNIQUE (user_id, preparation_space_id, activity_date)` — powers streaks and the heatmap
without scanning the attempt log. `preparation_space_id NULL` = the cross-space daily roll-up
that gives you global stats alongside per-space ones (requirement 20).

### `study_plans`
`id`, `user_id`, `preparation_space_id`, `plan_date` date, `items` jsonb,
`generated_at`, `completed_at`, `model_version`. The materialised "today's recommended
session" so the dashboard is one row read, not a live engine run.

---

## 9. Study materials

- `study_materials` — `id`, `preparation_space_id`, `title`, `kind`
  (`TEXT`/`MARKDOWN`/`PDF`/`CSV`/`JSON`/`LINK`), `content` text NULL, `storage_key` NULL,
  `byte_size`, `checksum`, `created_at`.
- `material_links` — `material_id`, `subject_id` NULL, `topic_id` NULL. Many-to-many, so
  one set of React notes can attach to `React -> Hooks` *and* `JavaScript -> Closures`.
- `material_chunks` — `id`, `material_id`, `position`, `content`, `token_estimate`,
  `embedding vector(1536) NULL`. Written from Phase 7 onward; the `embedding` column and
  pgvector stay unused (and the extension uninstalled) until AI arrives in Phase 8. The
  column is reserved now purely so adding it later is not a table rewrite.

---

## 10. AI (schema reserved in Phase 1, used in Phase 8)

- `ai_requests` — `id`, `user_id`, `preparation_space_id`, `feature`, `provider`, `model`,
  `prompt_hash`, `tokens_in`, `tokens_out`, `cost_micros`, `latency_ms`, `status`, `error`,
  `created_at`. Cost and latency are logged from the first AI call, not bolted on after the
  first surprising bill.
- `generated_content` — `id`, `preparation_space_id`, `feature`, `source_material_id` NULL,
  `payload` jsonb, `status` (`DRAFT`/`APPROVED`/`REJECTED`), `reviewed_at`,
  `created_question_id` NULL, `ai_request_id`, `created_at`.

**Nothing generated by AI enters `questions` without passing through
`generated_content.status = APPROVED`.** A staging table is the difference between "AI
helps me build my bank" and "AI quietly corrupted my study data and I found out during an
exam."

---

## 11. Conventions applied everywhere

1. `preparation_space_id` on every per-space table, first column of every composite index.
2. Soft delete via `archived_at`; nothing referenced by an attempt is ever hard-deleted.
3. Vocabularies (`type`, `mode`, `status`, `difficulty`) are `varchar` + check constraint,
   never Postgres `ENUM`.
4. `jsonb` is used for *shape variation* (question payloads, blueprints, breakdowns) and
   never for anything you filter or sort on — those get promoted to real columns.
5. Every derived table is reproducible from `question_attempts` alone. A `RebuildDerivedState`
   job exists from Phase 5 and is part of the test suite.
6. `created_at` / `updated_at` on everything, set by the database, not the JVM.
