# PATIPP — Working Agreement

Personalized Adaptive Test & Interview Preparation Platform.

**Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before writing any code.**
Data model: [docs/DATA-MODEL.md](docs/DATA-MODEL.md) ·
Engine: [docs/ADAPTIVE-ENGINE.md](docs/ADAPTIVE-ENGINE.md) ·
Plan: [docs/ROADMAP.md](docs/ROADMAP.md)

---

## The prime directive

This is **not an exam app**. It is a preparation platform where an exam is one mode among
several. Before adding anything, ask: *does this work for an interview, a certification and
a coding test too?* If it only works for exams, it is in the wrong place.

Concretely:
- Never add an `Exam` root entity. An exam is a `study_session` with `mode = EXAM`.
- Never subclass by preparation type. Types are `blueprint` rows plus strategy keys.
- Never put user performance state on a `questions` row.
- Never write a per-space query without `preparation_space_id`.

## How we work (non-negotiable)

1. Explain what we are building and the architecture behind it, before writing code.
2. Inspect what already exists first. Never overwrite working code unnecessarily.
3. Build in phases. **Do not start the next phase until the current one is verified working.**
4. Run the build and the tests. Fix what breaks. Report failures honestly with output.
5. Finish by stating what was implemented and how to test it manually.
6. No large unexplained code dumps.

## Standards

- Production quality, not prototype. Validation, error handling, and security are part of
  the feature, not a follow-up.
- Secrets come from environment variables. Never hardcode a key, token or connection
  string. `.env` is git-ignored; `.env.example` documents every key.
- Flyway migrations only. `spring.jpa.hibernate.ddl-auto=validate`, never `update`.
- `varchar` + check constraint for vocabularies, never Postgres `ENUM`.
- Soft delete (`archived_at`) for anything an attempt can reference.
- `question_attempts` is append-only and immutable.
- `adaptive` and `scheduling` stay pure: no Spring, no JPA, no web types. Enforced by
  ArchUnit.
- Every derived table must be rebuildable from the attempt log.

## Commands

```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d db   # postgres
set -a && . ./infra/.env && set +a                      # load secrets first
cd backend  && ./mvnw spring-boot:run                 # api on :8081
cd backend  && ./mvnw test                            # backend tests
cd frontend && npm run dev                            # web on :3000
cd frontend && npm run build && npm run lint          # frontend
```

## Current status

Architecture approved. Phases 0 and 1 complete and verified.
Next: **Phase 2 — Question Engine** (see docs/ROADMAP.md).

Running: Postgres in Docker, API on :8081, web on :3000.
(8080 is taken by another local project, so PATIPP uses 8081.)
