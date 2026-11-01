# CI and automatic deployment

The intended flow is:

```
branch -> pull request -> CI passes -> merge to main -> hosting deploys -> verify live app
```

CI checks changes. CD publishes them. Native Render/Vercel integrations provide CD;
GitHub Actions provides the missing tests and builds. No new hosting service or deploy
token is needed for this setup.

## What is committed

`.github/workflows/ci.yml` runs on pull requests, pushes to `main`, and manual dispatch.

- **Backend tests and package:** Java 21, the Maven wrapper, every test, and JAR packaging.
  Integration tests start their own disposable PostgreSQL 16 database with Testcontainers.
  GitHub-hosted Ubuntu runners supply Docker. Production database credentials are not used.
- **Frontend checks and build:** Node 24, reproducible `npm ci`, lint, challenge consistency
  tests, and the Next.js production build. Vercel separately rebuilds with its real environment.
- Both jobs run in parallel, cache dependencies, have timeouts, use read-only repository
  permissions and pin official actions to verified commit hashes. Backend test reports are
  retained for seven days if a job fails.

`render.yaml` requests `autoDeployTrigger: checksPass`. This affects Blueprint-managed
services only; it does not reconfigure a service created manually in the dashboard.

## Connect the existing hosting once

### Render API

1. Open the existing API service, then **Settings**.
2. Confirm its connected repository is `prosperDBdev/PATIPP`, branch `main`.
3. For a manually created Docker service, keep Root Directory `backend`, Dockerfile
   `./Dockerfile`, and Docker Context `.` relative to that root.
4. Set **Auto-Deploy → After CI Checks Pass**. If it is a Blueprint service, sync the
   updated Blueprint and confirm that setting.
5. Keep Neon credentials and JWT secrets in Render's environment. Keep health checks on
   `/actuator/health`. Flyway applies migrations on backend startup.

Do not create another service or database. A service deployed from a public URL without
a connected Git provider cannot use native auto-deploy; connect the GitHub repository first.

### Vercel frontend

1. Open the existing frontend project's **Settings → Git** and confirm the same repository.
2. Set its production branch to `main` and Root Directory to `frontend`.
3. Confirm `NEXT_PUBLIC_API_BASE_URL` is the frontend's own HTTPS origin and
   `PATIPP_API_ORIGIN` is the real Render API origin. Keep the existing `/api` rewrite.
4. Git-connected Vercel projects deploy pushes automatically. To prevent production
   promotion before CI passes, configure GitHub Deployment Checks for both exact job names:
   **Backend tests and package** and **Frontend checks and build**. Check availability in
   the project's dashboard; committing a workflow alone does not enable that gate.

If Vercel Deployment Checks are unavailable, use pull requests and required CI checks
before merging to `main`. This protects the merge path; direct pushes to `main` can still
deploy immediately. Configure a GitHub branch rule/ruleset to require pull requests and
both checks, with bypass restricted as appropriate for the repository.

## Practise the workflow

1. Create a feature branch and make a small change.
2. Push it and open a pull request. Inspect both jobs in GitHub's **Actions** tab.
3. Introduce a failing assertion on that branch to see a red check. Fix it and push again.
4. Merge after checks pass. Check Render and Vercel each deploy the intended commit.
5. Wait for both hosts to finish, then test sign-in, reload, and open the challenge.
   The two hosts deploy independently; CI passing does not mean both are live yet.

To undo application behaviour, revert the relevant commit and push the revert through CI.
Do not delete applied Flyway migrations: use a new forward migration for schema corrections.

## Current limits

The committed pipeline can be reviewed locally. Its first run on GitHub verifies the actual
hosted runner. Repository and provider dashboard settings require an account with access;
the files alone do not prove those settings are active. A successful push is not proof of
a successful deployment. Optional Google sign-in also needs matching client IDs configured
on Render and Vercel, as described in `DEPLOYMENT.md`.

Official references:
- https://render.com/docs/deploys
- https://render.com/docs/blueprint-spec
- https://vercel.com/docs/git/vercel-for-github
- https://vercel.com/docs/deployment-checks
