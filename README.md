# PATIPP

**P**ersonalized **A**daptive **T**est & **I**nterview **P**reparation **P**latform.

One preparation platform that adapts itself to whatever you are preparing for — a semester
exam, a backend interview, a cloud certification, a coding test. An exam is not a special
case in this codebase; it is one *mode* of a shared preparation engine.

> Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before contributing. The design rules
> there are what keep this from collapsing back into an exam app.

## Stack

| Layer | Choice |
|---|---|
| Backend | Spring Boot 4.1.1, Java 21, modular monolith |
| Database | PostgreSQL 16, Flyway migrations |
| Frontend | Next.js 16 (App Router), TypeScript, Tailwind CSS 4 |
| Auth | JWT access token + rotating refresh cookie |
| Local infra | Docker Compose |

## Getting started

**Prerequisites:** JDK 21, Node 20+, Docker. Maven is *not* required — the wrapper
(`backend/mvnw`) is committed.

```bash
# 1. Configure local secrets (never committed)
cp infra/.env.example infra/.env
#    then edit infra/.env - at minimum set POSTGRES_PASSWORD, PATIPP_DB_PASSWORD
#    and PATIPP_JWT_SECRET. Generate values with:  openssl rand -base64 48

# 2. Start PostgreSQL
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d db

# 3. Start the API on :8081  (loads infra/.env into the environment first)
set -a && . ./infra/.env && set +a
cd backend && ./mvnw spring-boot:run

# 4. Start the web app on :3001
cd frontend && npm install && npm run dev
```

Health check: `curl http://localhost:8081/actuator/health` → `{"status":"UP"}`

## Commands

```bash
cd backend  && ./mvnw test            # unit + integration tests (Testcontainers)
cd backend  && ./mvnw verify          # full build
cd frontend && npm run build          # production build + typecheck
cd frontend && npm run lint

docker compose -f infra/docker-compose.yml --env-file infra/.env down      # stop
docker compose -f infra/docker-compose.yml --env-file infra/.env down -v   # stop + wipe data
```

## Layout

```
backend/    Spring Boot modular monolith (com.patipp.*)
frontend/   Next.js App Router application
infra/      Docker Compose + environment template
seeds/      Starter question packs (JSON)
docs/       Architecture, data model, adaptive engine, roadmap
```

## Secrets

`infra/.env` is git-ignored and must never be committed. `infra/.env.example` documents
every key with a placeholder. The application reads all secrets from the environment; no
key, token or connection string belongs in source.

## Status

| Phase | State |
|---|---|
| 0 — Scaffolding | complete |
| 1 — Foundation (auth, spaces, curriculum) | complete |
| 2 — Question engine (bank, import, export) | complete |
| 3 — Practice sessions (session engine, attempt log) | complete |
| 4 — Exam mode | next |
| 5–10 | see [docs/ROADMAP.md](docs/ROADMAP.md) |
