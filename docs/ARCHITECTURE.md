# PATIPP — Architecture Proposal

**P**ersonalized **A**daptive **T**est & **I**nterview **P**reparation **P**latform

Status: **approved.** Phases 0 and 1 are implemented and verified; Phases 2-10 remain a proposal.
Where implementation revised a decision, the revision is noted inline.

---

## 1. The one idea the whole system is built on

Everything hangs off a single sentence:

> A **Preparation Space** is a self-contained world in which a user prepares for one thing.

Not "an exam." Not "a course." A *preparation space*. It owns its own topics, its own
question bank, its own performance history, its own readiness score, its own difficulty
rules and its own session formats.

"NIIT Semester 2", "Java Backend Interview" and "AWS Certification" are three preparation
spaces belonging to one user. They share **zero** state and **100%** of the engine.

If we get this right, adding "PMP Certification" or "System Design Interview" later is a
data change, not a code change. If we get it wrong, the app gets rewritten.

### The corollary rule

> **No table, service, or endpoint in the core may treat "exam" as a special case.**

An exam is not a first-class concept. It is a *Session* with `mode = EXAM`, a time limit,
deferred feedback and a fixed blueprint. An interview is a *Session* with `mode = INTERVIEW`
and conversational turns. A flashcard review is a *Session* with `mode = FLASHCARD_REVIEW`.
One session engine, one attempt log, one adaptive selector, one analytics pipeline.

This is the single most important decision in this document.

---

## 2. How preparation types stay extensible (the actual mechanism)

The naive approach — **which we are not doing**:

```
Session
 ├── ExamSession
 ├── InterviewSession
 ├── CodingTestSession
 └── CertificationSession      <- 4 classes, 4 repos, 4 controllers, 4 sets of bugs
```

That is a class explosion. Every new preparation type multiplies surface area, and every
cross-cutting feature (spaced repetition, analytics, offline sync) must be re-implemented
per subclass. It is exactly how an "exam app" becomes impossible to generalise.

### What we do instead: data-driven blueprints + a strategy registry

A preparation type is **a row of configuration**, not a subclass.

```jsonc
// preparation_types.blueprint  (jsonb)
{
  "key": "TECHNICAL_INTERVIEW",
  "allowedQuestionTypes": ["TECHNICAL", "BEHAVIORAL", "SCENARIO", "CODING", "SHORT_ANSWER"],
  "sessionModes":         ["PRACTICE", "INTERVIEW", "FLASHCARD_REVIEW"],
  "scoringPolicy":        "RUBRIC_WEIGHTED",
  "difficultyPolicy":     "ELO_TARGETED",
  "schedulerPolicy":      "FSRS_V1",
  "readinessWeights":     { "coverage": 0.15, "accuracy": 0.30, "depth": 0.25,
                            "retention": 0.10, "consistency": 0.10, "mock": 0.10 },
  "evaluationCriteria":   ["correctness", "completeness", "technicalAccuracy", "clarity"],
  "defaults":             { "sessionLength": 8, "timePerQuestionSec": 180,
                            "followUpsEnabled": true, "immediateFeedback": true },
  "difficultyLadder":     ["EASY", "MEDIUM", "HARD", "EXPERT"]
}
```

The engine reads the blueprint. Behaviour that *genuinely* differs is resolved through small
strategy interfaces, each backed by a registry:

| Interface | Resolved by | Default implementation |
|---|---|---|
| `AnswerEvaluator` | question type | exact / set-match / numeric evaluators |
| `ScoringPolicy` | `scoringPolicy` key | `SIMPLE_CORRECTNESS` |
| `DifficultyPolicy` | `difficultyPolicy` key | `ELO_TARGETED` |
| `ReviewScheduler` | `schedulerPolicy` key | `FSRS_V1` |
| `SessionModeHandler` | session `mode` | `PRACTICE` handler |
| `ReadinessModel` | space override -> type -> global | `WEIGHTED_V1` |

```java
public interface ScoringPolicy {
    String key();                                    // "RUBRIC_WEIGHTED"
    ScoreResult score(SessionSnapshot session, List<AttemptResult> attempts);
}

@Component
class ScoringPolicyRegistry {
    private final Map<String, ScoringPolicy> byKey;  // Spring injects every implementation

    ScoringPolicy resolve(String key) {
        return byKey.getOrDefault(key, byKey.get("SIMPLE_CORRECTNESS"));
    }
}
```

**Adding a preparation type therefore has three tiers of cost:**

1. **Free** — insert a `preparation_types` row reusing existing policy keys.
   ("PMP Certification" = the certification blueprint with different defaults.) No deploy.
2. **Cheap** — write one `@Component` implementing one strategy interface, register a key.
   (A language oral exam needs a pronunciation evaluator; nothing else changes.)
3. **Real work** — only when the type needs a genuinely new interaction model
   (e.g. a whiteboard system-design session needs a new `SessionModeHandler` plus UI).

Tier 3 is rare and is the honest cost of a real new feature. Tiers 1 and 2 cover every
type on your list.

### Guardrail

Every strategy must have a working default, and an unknown policy key **degrades to the
default rather than throwing**. This is what lets you create a "Custom" space at 2am and
have it just work.

---

## 3. System topology

```
+------------------------------------------------------------------+
|  Next.js 16 (App Router) - TypeScript - Tailwind - PWA           |
|  Server Components for reads - Route Handlers proxy auth          |
|  Dexie/IndexedDB mirror + Workbox SW + outbox sync queue          |
+-------------------------------+----------------------------------+
                                | REST/JSON, JWT bearer
+-------------------------------v----------------------------------+
|  Spring Boot 4.1 - Java 21 - MODULAR MONOLITH                    |
|                                                                   |
|  web           -> controllers, DTOs, validation, error mapping    |
|  application   -> use-case services, transactions, orchestration  |
|  domain        -> entities + PURE engine (no Spring, no JPA leak) |
|  infrastructure-> JPA repos, Flyway, AI adapters, security        |
+-------------------------------+----------------------------------+
                                |
                    +-----------v------------+
                    |  PostgreSQL 16         |
                    |  (jsonb + GIN indexes) |
                    +------------------------+
```

One deployable backend. One database. **No microservices.** Modularity is enforced by
package structure and ArchUnit tests, not by network boundaries.

### Why a modular monolith is right here

You are one developer building for one primary user. Microservices would buy independent
scaling you do not need and cost you distributed transactions, partial-failure handling,
and N deployment pipelines. What actually matters is that the *seams* are real — the
adaptive engine must be extractable later — and package boundaries plus interfaces give
you those seams at zero operational cost.

---

## 4. Backend module map

```
com.patipp
├── common/            cross-cutting: errors, ids, paging, clock, jsonb converters
├── auth/              registration, login, JWT issue/refresh, revocation
├── users/             profile, preferences, global stats, streaks
├── preparations/      * preparation spaces, preparation types + blueprints
├── curriculum/        subjects, topics, subtopics (a tree, scoped to a space)
├── questions/         question bank, versions, typed payloads, tags, validation
├── attempts/          immutable attempt event log + answer evaluation
├── sessions/          * unified session engine (practice/exam/interview/flashcard)
├── interviews/        interview turns, rubric evaluation, follow-up chaining
├── adaptive/          * learner model, question selection, difficulty targeting
├── scheduling/        * spaced repetition, review queues, due debt
├── analytics/         aggregates, snapshots, readiness score, breakdowns
├── recommendations/   "what should I do now" - daily plan composition
├── materials/         study material import (text/MD/JSON/CSV/PDF), chunking, linking
├── imports/           question-bank import/export, format adapters, dry-run validation
├── ai/                * provider-agnostic ports + adapters + content staging
└── sync/              offline outbox ingestion, idempotency, conflict policy
```

`*` = designed to be independently evolvable. Those four — `preparations`, `sessions`,
`adaptive`, `scheduling` — are the load-bearing walls.

### Dependency rule (enforced by ArchUnit from Phase 1)

```
web  ->  application  ->  domain  <-  infrastructure
```

- `domain` depends on **nothing** — no Spring, no JPA annotations on engine types.
- `adaptive` and `scheduling` may not import `web`, `sessions`, or any JPA entity. They
  consume plain input records and return plain output records.
- Modules talk to each other through published interfaces in `<module>/api`, never by
  reaching into another module's internal packages.

Why so strict on `adaptive`? Because you *will* rewrite the selection algorithm two or
three times, and you must be able to unit-test it against a hand-written learner model in
twenty lines with no database and no Spring context.

---

## 5. The adaptive engine, in one picture

```
                 +---------------------------+
  attempts  ---> |  LearnerModel builder     |   per (user x space)
  (event log)    |  ability per topic,       |
                 |  mastery, coverage,       |
                 |  recency, volatility      |
                 +-------------+-------------+
                               |
       +-----------------------+-----------------------+
       v                       v                       v
+--------------+     +-------------------+    +------------------+
|  Scheduling  |     | QuestionSelector  |    | ReadinessModel   |
|  (FSRS)      |---->| composite scorer  |    | transparent      |
|  what is due |     | + constraints     |    | weighted parts   |
+--------------+     +---------+---------+    +------------------+
                               v
                    the next questions to serve
```

- **LearnerModel** — a read-only snapshot, cheap to build, cached per space.
- **Scheduling** — decides *when* an item comes back. Owns no selection logic.
- **QuestionSelector** — decides *what* to serve now, from due items + weakness + ability.
- **ReadinessModel** — decides *how prepared you are*, and can always explain itself.

Each is an interface with a versioned implementation (`FSRS_V1`, `ELO_TARGETED_V1`,
`WEIGHTED_V1`). Replacing one never touches the others. Full detail in
[ADAPTIVE-ENGINE.md](ADAPTIVE-ENGINE.md).

---

## 6. Frontend architecture

```
frontend/src
├── app/
│   ├── (auth)/                    login, register
│   ├── (app)/
│   │   ├── layout.tsx             shell: sidebar, space switcher, command palette
│   │   ├── page.tsx               daily dashboard ("what should I do now")
│   │   ├── spaces/[spaceId]/
│   │   │   ├── page.tsx           space overview + readiness
│   │   │   ├── practice/          practice runner
│   │   │   ├── exam/              exam runner (server-authoritative timer)
│   │   │   ├── interview/         interview runner (conversational)
│   │   │   ├── flashcards/        review runner
│   │   │   ├── questions/         bank management + import
│   │   │   ├── materials/         study material
│   │   │   └── analytics/         charts
│   │   └── settings/
├── components/
│   ├── ui/                        primitives (shadcn-style, owned in-repo)
│   ├── question/                  * one renderer per question type, registry-resolved
│   ├── session/                   shared runner chrome: progress, nav, timer, palette
│   └── charts/                    Recharts wrappers
├── lib/
│   ├── api/                       typed client generated from the OpenAPI spec
│   ├── offline/                   Dexie schema, outbox, sync engine
│   ├── engine/                    client-side scoring for offline sessions
│   └── keyboard/                  shortcut registry
└── stores/                        session runner state (Zustand)
```

**The question renderer registry mirrors the backend evaluator registry.** A new question
type is added by registering `{ type: 'MULTI_SELECT', Render: MultiSelectQuestion }` in one
map. The session runner itself never switches on question type — if it ever does, the
abstraction has leaked and we fix it there.

### PWA / offline

- Workbox service worker: app shell precached; API reads stale-while-revalidate.
- Dexie mirrors a *question pack* per space (questions, topics, due schedule).
- Attempts are written locally with a client-generated UUID and queued in an **outbox**.
- Sync posts the outbox; the server is idempotent on `client_attempt_id` and recomputes all
  derived state server-side. Attempts are append-only events, so there are no merge
  conflicts by construction — the worst case is duplicate suppression.
- AI features are explicitly online-only and visibly disabled when offline.

---

## 7. Repository layout

```
PATIPP/
├── docs/                    architecture, data model, adaptive engine, roadmap, ADRs
├── backend/
│   ├── mvnw / mvnw.cmd      Maven Wrapper (no global Maven install required)
│   ├── pom.xml
│   ├── src/main/java/com/patipp/...
│   ├── src/main/resources/db/migration/   Flyway V1__*.sql
│   ├── src/main/resources/prompts/        versioned AI prompt templates
│   └── src/test/java/...                  unit + slice + Testcontainers integration
├── frontend/
│   ├── package.json
│   └── src/...
├── infra/
│   ├── docker-compose.yml   postgres + backend + frontend
│   └── .env.example         NEVER commit the real .env
├── seeds/                   starter question packs (HTML/CSS/JS/React/RN) as JSON
├── CLAUDE.md                working agreement for this project
└── README.md
```

Single repository, two build systems, one `docker compose up`. No Nx/Turborepo — pure
overhead for two projects.

---

## 8. Security and configuration posture

- **JWT** — short-lived access token (15 min) plus a rotating refresh token in an httpOnly,
  SameSite=Strict cookie. Refresh tokens are stored hashed and are individually revocable.
  The access token lives in memory on the client, never in `localStorage`.
- **Passwords** — BCrypt (strength 12) via Spring Security.
- **Secrets** — environment variables only, from a git-ignored `.env` locally and the
  platform secret store in deployment. `.env.example` documents every key with a dummy
  value. No key, token or connection string is ever written into source.
- **Validation** — Jakarta Bean Validation on every request DTO, plus a JSON-Schema check
  on every question payload before persistence. Reject at the edge.
- **Authorization** — every query is scoped by `user_id` *and* `preparation_space_id`. A
  shared `SpaceAccessGuard` asserts ownership once per request, and repository methods take
  the space id as a mandatory parameter so it cannot be silently forgotten.
- **Rate limiting** on auth endpoints and on AI endpoints (which cost real money).
- **Errors** — RFC 7807 `ProblemDetail` responses, no stack traces leaked, a correlation id
  on every response.
- **Exam integrity** — the timer is server-authoritative. The client renders a countdown;
  the server records `started_at` and rejects submissions past `started_at + duration`.

---

## 9. Architectural mistakes to avoid

These are the specific ways this project would fail. Each is cheap to prevent now and
expensive to fix later.

| # | Mistake | Why it hurts | Prevention |
|---|---|---|---|
| 1 | Making `Exam` a root entity | Interviews and flashcards get bolted on as parallel systems; three attempt logs; analytics can never unify | One `sessions` table with a `mode` |
| 2 | Subclass-per-preparation-type | Class explosion; every cross-cutting feature written N times | Blueprint rows + strategy registry (section 2) |
| 3 | Storing review state on the `questions` row | Question banks become un-shareable; the same question in two spaces overwrites its own history | Separate `learning_states` keyed by (user, space, question) |
| 4 | Adding `preparation_space_id` "later" | It is a partition key on ~8 tables and a filter on nearly every query; retrofitting touches everything | Scope by space from migration V1 |
| 5 | Table-per-question-type | 9 tables, 9 repos, joins everywhere; a new type needs DDL | Hybrid promoted columns + `jsonb` payload |
| 6 | Pure EAV for question content | Nothing is queryable; every read reassembles rows | Same hybrid |
| 7 | Adaptive engine importing JPA entities | Cannot unit-test without a database; algorithm changes become migrations | Pure domain records in and out; no Spring in `adaptive` |
| 8 | Computing analytics from raw attempts on every request | The dashboard gets slower every week you study | Incremental aggregates + dated snapshots |
| 9 | Mutable attempt rows | You lose the ability to recompute history when the algorithm changes | Attempts are append-only and immutable |
| 10 | Storing readiness as a bare number | You cannot answer "why did it drop?", which is the entire point of the metric | Persist the component breakdown alongside the score |
| 11 | Trusting the client for exam timing | Trivially bypassed, and then your own scores mean nothing | Server-authoritative start time and deadline |
| 12 | Postgres `ENUM` types for evolving vocabularies | `ALTER TYPE` in migrations is painful; a new question type needs DDL | `varchar` + lookup table or check constraint |
| 13 | Editing a question in place after it has been attempted | Your history silently becomes wrong | `question_versions`; attempts pin a version id |
| 14 | Hard-deleting questions | FK violations against your own history | Soft delete via `archived_at` |
| 15 | AI as a required dependency | No key or no internet means a dead app, plus unbounded cost | AI is a port with a `Noop` adapter; every AI feature has a non-AI fallback |
| 16 | Writing AI output straight into the bank | One bad generation quietly poisons your study data | Staging table with a DRAFT -> APPROVED review step |
| 17 | Microservices / Kafka / Kubernetes now | Months of yak-shaving for zero user-visible value | Modular monolith; revisit only under real load |
| 18 | Building exam mode before the question engine | Nothing real to test with, guaranteed rework | Follow the phase order |

---

## 10. What belongs in the MVP

The MVP is the smallest thing that makes you **stop using other tools**. That threshold is:
*I can put my NIIT material in, practise it daily, sit a mock, and see where I am weak.*

**In:**

- Auth (register / login / refresh)
- Preparation spaces (create, switch, edit) with preparation types as data
- Subjects -> topics tree
- Question bank: MCQ, Multi-select, True/False, Short Answer, with explanations
- Import: JSON and CSV, with dry-run validation before commit
- Practice sessions (untimed), answer submission, immediate feedback, attempt history
- Exam mode: N questions, duration, navigation, mark-for-review, auto-submit, results with
  per-topic breakdown
- Weak-topic detection and adaptive selection v1 (weakness + due + difficulty targeting)
- Spaced repetition v1 covering both questions and flashcards
- Dashboard: readiness score with a visible breakdown, accuracy, streak, weakest topics
- Responsive, installable PWA shell with offline read and offline practice of cached packs

**Out, deliberately:**

- All AI features (Phase 8) — the app must be complete and useful without them
- Code execution / test-case running for coding questions (store and self-grade first)
- Interview follow-up chaining (Phase 8; a static interview question bank ships earlier)
- PDF import (start with text / Markdown / JSON / CSV)
- Multi-user sharing, public question packs, anything social
- Full bidirectional offline sync (start with offline-read plus outbox-write)

**Why this cut:** every deferred item depends on a kept item being correct first. AI
generation writes into the question bank, so the question bank must be right. Interview
evaluation needs the session engine. Code execution is a sandboxing project in its own
right and has nothing to do with whether the platform works.

---

## 11. Key decisions, recorded

| Decision | Choice | Reasoning |
|---|---|---|
| Backend build | **Maven + Wrapper** | Spring's default; the wrapper needs no global install, and you currently have neither Maven nor Gradle |
| Java | **21 LTS** | Already installed; records, sealed interfaces and pattern matching make the domain model genuinely cleaner |
| Spring Boot | **4.1.1** | Current release. Note the Boot 4 differences we hit: `spring-boot-starter-webmvc` (not `-web`), a dedicated `-flyway` starter, per-module test starters instead of one `spring-boot-starter-test`, Jackson 3 (`tools.jackson`) at runtime, and `@AutoConfigureMockMvc` now in `org.springframework.boot.webmvc.test.autoconfigure` |
| Migrations | **Flyway** | Explicit reviewable SQL; `ddl-auto: validate` always, never `update` |
| Question content | **Hybrid columns + jsonb** | Extensible without DDL, still queryable and indexable |
| Auth | **JWT + rotating refresh cookie** | Per your spec; upgradeable to OAuth later with no model change |
| Frontend | **Next.js 16 App Router + TS + Tailwind** | Per your spec |
| Charts | **Recharts** | Lightweight, idiomatic React API, sufficient for these charts |
| Offline store | **Dexie (IndexedDB)** | Structured and queryable; far better than localStorage for question packs |
| Testing | **JUnit 5 + Testcontainers + Vitest + Playwright** | Real Postgres in tests; Playwright for the exam-runner flows that actually matter |
| Deployment | **Docker Compose locally; decide hosting at Phase 10** | No value in deciding now |
