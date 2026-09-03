# PATIPP — Development Roadmap

Ten phases. **No phase begins until the previous one builds, passes its tests, and has been
manually verified by you.** Each phase below states what gets built, what "done" means, and
exactly how you check it by hand.

Effort estimates assume focused sessions, not calendar time.

**Progress: Phases 0, 1 and 2 complete.** 117 backend tests plus 77 end-to-end API
checks green; frontend builds and lints clean.

---

## Phase 0 — Scaffolding (½ session)

Not in your original list, but Phase 1 is much cleaner if this exists first.

**Status: COMPLETE.**

**Build:** repo skeleton, `backend/` with Maven Wrapper committed (you have no global Maven,
the wrapper solves it), `frontend/` via `create-next-app`, `infra/docker-compose.yml` with
Postgres 16, `.env.example`, `.gitignore`, `CLAUDE.md`, `git init` + first commit.

**Done when:** `docker compose up -d db` gives a healthy Postgres; `./mvnw test` and
`npm run build` both pass on empty projects.

**You verify:** `curl localhost:8081/actuator/health` returns `{"status":"UP"}`. (8081, because 8080 was already taken by another local project.)

---

## Phase 1 — Foundation (2–3 sessions) · MVP

**Status: COMPLETE.** 54 backend tests and 37 end-to-end API checks pass; frontend builds and lints clean.

**Build**
- Flyway `V1__baseline.sql`: users, refresh_tokens, preparation_types, preparation_spaces,
  subjects, topics. `V2` seeds the six system preparation types.

  > Revised during implementation. This originally said V1 would also create the
  > reserved-but-unused tables for later phases. That was wrong: fifteen tables nothing can
  > exercise yet invites schema drift, and Flyway exists precisely so Phase 2 can ship a
  > `V3`. Each phase now brings its own migration.
- Spring Security + JWT: register, login, refresh (rotating cookie), logout, `/me`.
- Preparation space CRUD; seed the six system preparation types with their blueprints.
- Subject and topic CRUD, topics as a tree.
- `SpaceAccessGuard` + repository conventions so nothing can leak across spaces.
- ArchUnit tests enforcing the module dependency rules from day one.
- Global error handling (RFC 7807), OpenAPI generation.
- Frontend: auth pages, app shell, space switcher, create-space flow, curriculum editor,
  dark/light mode.

**Exit criteria**
- Integration tests (Testcontainers) cover register -> login -> create space -> add subject
  -> add topic.
- An ArchUnit test **fails** if `adaptive` imports a JPA entity. (Verify the guard works by
  breaking it once, then reverting.)
- A cross-user access attempt returns 404, not 403 — do not confirm the existence of other
  users' resources.

**You verify by hand:** register, create "NIIT Semester 2" (Academic Exam, target Oct 2026,
target 85%), add HTML/CSS/JavaScript/React/React Native, add subtopics under React. Then
create "Java Backend Interview" as a second space and confirm it is completely empty — no
bleed-through.

---

## Phase 2 — Question Engine (2–3 sessions) · MVP

**Status: COMPLETE.**

**Build**
- `questions` + `question_versions` + `question_stats`, versioning on edit.
- Sealed `QuestionContent` hierarchy, validated per type at the edge.
- Types now: MCQ, MULTI_SELECT, TRUE_FALSE, SHORT_ANSWER, FLASHCARD.
  (LONG_ANSWER/SCENARIO/CODING arrive with the phases that use them.)
- ~~`AnswerEvaluator` registry with the evaluator per type.~~

  > Revised during implementation. Evaluation lives **on the sealed content type** instead of
  > in a runtime registry. Preparation types need a registry because new ones arrive as data
  > and must work without a deploy; question formats are the opposite — a closed set known at
  > compile time. Sealing gives exhaustive switches, so adding a format and forgetting to
  > evaluate it fails the build rather than failing during an exam. Validation, by contrast,
  > is hand-written per format rather than JSON Schema, so the errors name the actual problem
  > ("exactly one option must be marked correct, but 2 are") instead of a schema path.
- Question CRUD, search, filter by topic/difficulty/type/tag, bulk archive.
- Import: JSON and CSV, **dry-run first** — parse, validate, report errors and duplicates
  by `content_hash`, then commit only on confirmation.
- Export to JSON (so your bank is never trapped in this app).
- Frontend: question list with filters, type-aware editor, import wizard with a preview
  table.
- Seed pack: 60 real HTML/CSS/JavaScript/React/React Native questions in
  `seeds/niit-semester-2.json`, 12 per subject, verified to import cleanly. Every later
  phase now has honest material to test against.

**Exit criteria:** round-trip test — export a space's bank, wipe it, re-import, assert
identity. Importing a malformed CSV changes nothing in the database.

**You verify:** paste a CSV of 20 JavaScript questions, see the dry-run report catch two
deliberate errors, fix, import, then edit a question and confirm version 2 exists while
version 1 is retained.

---

## Phase 3 — Practice Sessions (2 sessions) · MVP

**Build**
- `study_sessions` + `session_items` + `question_attempts`.
- `SessionModeHandler` registry; `PRACTICE` handler only.
- Start session (topic / difficulty / count / type filters, or "just give me something" —
  random for now, adaptive in Phase 5), serve item, submit answer, immediate feedback with
  explanation, complete session, summary.
- Attempt history per question; per-question stats update.
- Frontend: session runner with the chrome that every later mode reuses — progress bar,
  keyboard shortcuts (1–9 select, Enter submit, N next, M mark), feedback panel.

**Exit criteria:** every question type submits, evaluates and scores correctly, including
partial credit on multi-select. Attempts are immutable — an update attempt fails.

**You verify:** run a 10-question practice session, deliberately get 4 wrong, check the
summary and that history shows exactly 10 attempts with sensible response times.

---

## Phase 4 — Exam Mode (2 sessions) · MVP

**Build**
- `EXAM` session handler: server-set `started_at` and `deadline_at`, deferred feedback,
  blueprint-weighted sampling, randomisation with a stored seed (so a session is
  reproducible).
- Question navigation grid, mark-for-review, unanswered tracking, save-and-continue.
- Auto-submit on deadline (server-enforced; a submission at `deadline + 1s` is rejected).
- Results: total score, per-subject and per-topic breakdown, per-difficulty breakdown, time
  per question, all incorrect answers with explanations.
- Exam templates saved per space ("50 questions / 60 minutes / all subjects").

**Exit criteria:** a test that submits after the deadline gets rejected. Closing the browser
mid-exam and reopening resumes with the correct remaining time computed server-side.

**You verify:** sit a 20-question / 15-minute mock. Mark three for review, leave two blank,
let it auto-submit. Confirm the breakdown resembles the example in your requirement 10.

---

## Phase 5 — Adaptive Engine (3 sessions) · MVP

**Build** — see [ADAPTIVE-ENGINE.md](ADAPTIVE-ENGINE.md) for every formula.
- `com.patipp.adaptive`, pure, no Spring: `LearnerModel` builder, `AbilityEstimator`
  (Elo both sides), `QuestionSelector` (composite score + constraints + sampling).
- `topic_mastery` incremental updates on each attempt.
- Weak-topic and weak-subtopic detection with the 5-attempt confidence floor.
- Adaptive difficulty targeting 78% success, plus the recovery-mode guardrails.
- `selection_reason` written on every served item.
- `RebuildDerivedState` job — replay the attempt log, rebuild all derived tables.
- The replay/evaluation harness (Brier score calibration check).
- "Give me something to practice" now actually adaptive.
- Frontend: a "why this question?" affordance in practice mode.

**Exit criteria:** unit tests with synthetic learners — a learner strong in JS and weak in
React Native must receive a majority of React Native items. `RebuildDerivedState` after a
truncate reproduces identical state (this proves the three-layer separation holds).

**You verify:** deliberately fail six React Hooks questions, then start a practice session
and confirm Hooks dominates and the recommendation says to focus there. Check that difficulty
drops after a bad run rather than piling on.

---

## Phase 6 — Spaced Repetition & Flashcards (2 sessions) · MVP

**Build**
- `com.patipp.scheduling`: `ReviewScheduler` (`FSRS_V1`), `learning_states` lifecycle,
  grade derivation from ordinary quiz answers, priority bands.
- Due-queue query, review-debt metric, `FLASHCARD_REVIEW` session mode with
  Again/Hard/Good/Easy and interval previews on each button.
- Selector integration: due items now feed `DueScore`.
- Frontend: flashcard runner (flip animation, swipe on touch, 1–4 keyboard), due counters
  per topic.

**Exit criteria:** scheduler unit tests over a simulated 90-day study history produce
monotonically increasing intervals for consistently-correct items and interval collapse on
lapse. Flashcard reviews and MCQ answers both move the same `learning_states` row.

**You verify:** review 10 flashcards choosing different grades, confirm the previewed
intervals match what actually gets scheduled, and that "Again" brings the card back inside
the same session.

---

## Phase 7 — Analytics & Readiness (2 sessions) · MVP

**Build**
- `ReadinessModel` (`WEIGHTED_V1`) with all six components + the confidence factor.
- Nightly `readiness_snapshots` with breakdown, deltas and human-readable drivers.
- `daily_activity` roll-ups, streak calculation (timezone-correct).
- Daily dashboard: greeting, space, exam countdown, readiness, today's recommended session,
  prioritised weak topics.
- `study_plans` generation.
- Analytics page: accuracy over time, performance by difficulty, by question type, per
  topic mastery, mock-exam history, study-time heatmap. Recharts.

**Exit criteria:** readiness is reproducible from the event log and never exceeds the
confidence cap on thin data. Every chart answers a study question — if one does not, it is
cut.

**You verify:** the dashboard matches the shape of your requirement 12, and the readiness
panel explains a change in plain language.

> **This is the end of the MVP.** At this point you should be able to genuinely prepare for
> NIIT Semester 2 with this app and nothing else. Use it for a week before Phase 8.

---

## Phase 8 — AI Layer (3 sessions)

**Build**
- `com.patipp.ai` ports: `QuestionGenerationPort`, `ExplanationPort`, `AnswerEvaluationPort`,
  `TutorPort`, `FollowUpPort`.
- Adapters: `AnthropicAdapter`, `OpenAiAdapter`, `LocalModelAdapter` (Ollama-compatible),
  and **`NoopAdapter`** selected automatically when no key is configured — the app stays
  fully functional, AI buttons render disabled with a reason.
- Provider selected by config (`patipp.ai.provider`), model per feature, keys from env only.
- Versioned prompt templates in `resources/prompts/`, response schema validation, retries
  with backoff, per-user rate limit and monthly cost cap, `ai_requests` cost logging,
  response cache keyed by `prompt_hash`.
- Features: generate questions from study material -> `generated_content` **DRAFT** with a
  review-and-approve UI; explain a wrong answer; generate a similar question on the same
  concept; weak-topic mini-lesson followed by practice.
- Interview mode proper: `INTERVIEW` session handler, rubric evaluation across
  correctness / completeness / technical accuracy / clarity, AI-generated follow-ups
  chained via `interview_turns.parent_turn_id`, beginner / intermediate / advanced levels.
  Non-AI fallback: authored follow-ups from the question payload plus self-scoring against
  the rubric.

**Exit criteria:** with `AI_PROVIDER=none` and no keys present, **every test still passes
and every page still works.** Nothing AI-generated reaches `questions` without approval.

**You verify:** paste your React notes, generate 15 questions, reject 4, approve 11, then
run an interview session and get a follow-up that actually responds to what you said.

---

## Phase 9 — PWA & Offline (2 sessions)

**Build**
- Web app manifest, icons, installability, iOS splash screens.
- Workbox service worker: precached shell, stale-while-revalidate reads.
- Dexie schema mirroring question packs, topics, due schedule per space.
- Pack download per space, with size shown before download.
- Offline practice and flashcard review with client-side scoring; attempts written to a
  local outbox with `client_attempt_id`.
- Sync engine: flush outbox on reconnect, server-side idempotent ingest, derived state
  recomputed server-side, conflict-free by construction.
- Clear offline/online/syncing indicator; AI features visibly disabled offline.

**Exit criteria:** airplane-mode test — install, go offline, complete a 20-question session
and 15 flashcards, come back online, confirm all 35 attempts land exactly once and readiness
updates. Then repeat with a forced duplicate outbox flush and confirm no double-counting.

---

## Phase 10 — Polish, Hardening, Deploy (2–3 sessions)

**Build**
- Accessibility pass: keyboard-only run of every session mode, focus management, ARIA on
  the question runner, contrast audit, respects `prefers-reduced-motion`.
- Performance: query review with `EXPLAIN` on the dashboard and selector paths, N+1 hunt,
  frontend bundle budget, Lighthouse >= 90 across the board.
- Security: dependency audit, rate limits verified, JWT rotation and reuse-detection tests,
  input fuzzing on import endpoints, secrets audit (nothing in git history).
- Testing: Playwright end-to-end for exam, practice, flashcard and interview runners; the
  full-history replay test.
- Backup and restore: `pg_dump` script plus a documented full-export of your data.
- Deployment: production Docker build, frontend to Vercel, backend to a container host,
  managed Postgres, health checks, structured logging, error tracking.
- `README.md` and operational runbook.

---

## Sequencing rationale

The order is not arbitrary — each phase is the cheapest possible unblock for the next:

- **1 before everything** — `preparation_space_id` is a partition key; adding it later
  touches every table and every query.
- **2 before 3** — you cannot test a session engine without real questions.
- **3 before 4** — an exam is a session with constraints; building it first would mean
  writing the attempt pipeline twice.
- **4 before 5** — the adaptive engine needs a body of real attempt data to be tuned
  against, and mock exams are the yardstick it is tuned toward.
- **5 before 6** — scheduling plugs into a selector that must already exist.
- **7 after 5 and 6** — readiness consumes mastery and retention; building it earlier means
  building it twice.
- **8 after 7** — AI *writes into* the question bank and *reads from* the learner model.
  Both must be correct first, or AI amplifies existing bugs at scale and cost.
- **9 late** — offline mirrors a data model that must have stopped changing.
- **10 last** — polish what exists.

## Standing working agreement

For every phase: explain what we are building and why, inspect what already exists before
writing, make the changes, run the build and tests, fix what breaks, then report what was
implemented and exactly how you can verify it by hand. No phase is "done" on my say-so —
it is done when you have used it.
