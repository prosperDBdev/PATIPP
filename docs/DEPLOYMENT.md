# Deploying PATIPP

**Target:** frontend on Vercel at `patipp.ebitimi.dev`, API on Render, Postgres on Neon.

> Render rather than Fly.io. Both are always-on container hosts and either works; Render was
> chosen because it deploys from the GitHub repo through a browser and needs no CLI login,
> after the Fly device-code flow failed repeatedly. `backend/fly.toml` is left in place, so
> switching back is a `fly launch` away. Nothing else in the architecture changes — the
> Dockerfile is identical and the Vercel rewrite just points at a different hostname.

---

## Why this shape

Vercel has no Java runtime. Its native Functions runtimes are Node, Python, Go, Bun, Rust and
Ruby; a Dockerfile *can* run there as a container image, but those run as **stateless autoscaling
Functions**, and a Spring Boot application is close to the worst fit for that — a ~10 second JVM
and Spring startup on the critical path of whichever request happens to wake it, a JDBC connection
pool per instance, and Flyway migrations at every boot. So the API lives on a host built for
always-on containers, and Vercel serves the frontend.

### The rewrite is load-bearing, not a convenience

PATIPP's refresh token is an httpOnly cookie marked **`SameSite=Strict`**, chosen so the browser
never attaches it to a cross-site request. If the frontend were on `patipp.ebitimi.dev` and the API
on `patipp-api.fly.dev`, the browser would call that cross-site and **silently refuse to send the
cookie**. Sign-in would appear to work, then no session would survive a page reload — and it would
read as a bug in the auth code rather than a consequence of hosting.

`frontend/vercel.ts` therefore proxies `/api/*` to Fly, so the browser only ever sees one origin.
The alternative, relaxing the cookie to `SameSite=None`, would throw away CSRF protection that was
deliberate.

> Do not replace the rewrite with a redirect. A redirect sends the browser to the Fly origin and
> puts you straight back into the problem the rewrite exists to avoid.

---

## 1. Postgres — Neon through the Vercel Marketplace

Provision through the Marketplace rather than directly, so credentials are injected into the
Vercel project and stay connected across environments.

```bash
npm i -g vercel
vercel login                        # opens a browser
cd frontend && vercel link          # creates/links the Vercel project

vercel integration discover --category storage   # confirm the current catalogue
vercel integration add neon --yes
vercel env pull --yes                            # writes .env.local, git-ignored
```

Neon gives two connection strings. **Use the pooled one** (`...-pooler...`) for the API: HikariCP
opens its own pool, and a container that restarts takes its connections with it, so a server-side
pooler in front of Postgres is what keeps the connection count sane.

### Converting Neon's URL to JDBC

Neon hands out a libpq URL — `postgres://user:pass@host/db?sslmode=require&channel_binding=require`.
JDBC wants the host and database in the URL but the credentials passed separately:

```
PATIPP_DB_URL=jdbc:postgresql://<host>-pooler.<region>.aws.neon.tech/neondb?sslmode=require
PATIPP_DB_USER=<user>
PATIPP_DB_PASSWORD=<password>
```

> **Drop `channel_binding=require`.** It is a libpq connection parameter that the PostgreSQL
> JDBC driver does not implement. `sslmode=require` is what matters, and Neon is happy with it
> alone.

Put the Neon region and Fly's `primary_region` in the same place. A round trip across an ocean on
every query is the easiest self-inflicted latency there is.

---

## 2. API on Fly.io

Install `flyctl` once. **On Windows, in PowerShell** — the `curl | sh` line from Fly's docs is
for macOS and Linux and will not work in Git Bash:

```powershell
iwr https://fly.io/install.ps1 -useb | iex
```

```bash
# then, in any shell
fly auth login                # opens a browser

cd backend
fly launch --no-deploy        # accept the existing fly.toml; pick a region near Neon
```

Secrets never go in `fly.toml`. Set them with `fly secrets set`, which stores them encrypted and
restarts the machine:

```bash
fly secrets set \
  PATIPP_DB_URL='jdbc:postgresql://<host>-pooler.<region>.aws.neon.tech/patipp?sslmode=require' \
  PATIPP_DB_USER='<neon user>' \
  PATIPP_DB_PASSWORD='<neon password>' \
  PATIPP_JWT_SECRET="$(openssl rand -base64 48)"
```

`PATIPP_PORT`, `PATIPP_CORS_ORIGINS` and `PATIPP_SECURE_COOKIE` are already in `fly.toml` —
they are configuration, not secrets.

> **`PATIPP_JWT_SECRET` must be at least 32 bytes of real entropy.** The application refuses to
> start otherwise, which is intentional: a weak signing key is a silent total compromise of every
> session, and failing to boot is the only honest response to one.

Then:

```bash
fly deploy
fly logs                       # watch Flyway apply V1..V9 on the first boot
curl https://<app>.fly.dev/actuator/health
```

Flyway runs the migrations at startup. `ddl-auto` is `validate`, so if a migration and an entity
disagree the application refuses to start rather than quietly running against a wrong schema.

### Scaling notes

`fly.toml` sets `min_machines_running = 1` and `auto_stop_machines = false` deliberately. Letting
Fly suspend the machine would reintroduce the JVM cold start that choosing a container host was
meant to avoid. That is the trade: a small always-on cost, in exchange for an API that answers
immediately.

---

## 3. Frontend on Vercel

Two environment variables:

| Variable | Value | Why |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | `https://patipp.ebitimi.dev` | Same origin as the page, so the `SameSite=Strict` cookie is sent. The rewrite then forwards `/api/*` to Fly. |
| `PATIPP_API_ORIGIN` | `https://<app>.fly.dev` | Read by `vercel.ts` at build time to target the rewrite. Keeps the Fly hostname out of the committed config. |

```bash
cd frontend
vercel env add NEXT_PUBLIC_API_BASE_URL production   # https://patipp.ebitimi.dev
vercel env add PATIPP_API_ORIGIN production          # https://<app>.fly.dev
vercel --prod
```

### Domain

```bash
vercel domains add patipp.ebitimi.dev
```

Vercel prints the record to create. For a subdomain it is a `CNAME` to `cname.vercel-dns.com`.

`.dev` is on the HSTS preload list, so browsers will only ever speak HTTPS to it. Vercel issues the
certificate automatically — but it does mean there is no working HTTP fallback while DNS propagates,
so a half-configured domain fails closed rather than serving insecurely. That is the right
behaviour; just do not mistake it for a broken deploy.

---

## 4. Verify, in this order

Each step fails for a different reason, so doing them in order tells you which layer is wrong.

```bash
# 1. API is up and reached the database (Flyway ran, so the schema exists)
curl https://<app>.fly.dev/actuator/health          # {"status":"UP"}

# 2. The rewrite works — same path, through the Vercel domain
curl https://patipp.ebitimi.dev/api/v1/preparation-types   # 401, not 404
```

A **404** here means the rewrite is not matching. A **401** is correct: the endpoint exists and is
refusing an unauthenticated request.

Then in a browser, and this is the check that matters most:

3. Register an account at `https://patipp.ebitimi.dev`.
4. **Reload the page.** You must still be signed in.

Step 4 is the whole point of the rewrite. If a reload signs you out, the refresh cookie is not
coming back — check that `NEXT_PUBLIC_API_BASE_URL` is the Vercel domain and **not** the Fly URL,
because that single mistake reintroduces the cross-site problem while leaving everything else
looking fine.

---

## What is not set up here

Honest list, so none of it is a surprise later:

- **No CI.** Fly deploys on `fly deploy`, Vercel on push to `main`. The backend test suite is not
  gated on either, so a broken API can be deployed. Phase 10 covers this.
- **No backups.** Neon has point-in-time restore on paid plans; on the free tier there is nothing.
  The attempt log is the only irreplaceable data — every derived table can be rebuilt from it with
  `POST /api/v1/spaces/{id}/derived-state/rebuild`, but the log itself cannot be reconstructed.
- **Days are bucketed in UTC.** `users.timezone` is stored but unused, so streaks and the activity
  heatmap will attribute a late-evening session to the following day for anyone well west of UTC.
- **No error tracking.** Failures are in `fly logs` and nowhere else.
