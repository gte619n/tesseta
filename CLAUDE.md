# CLAUDE.md — project-wide guidance

## Architecture
- **Three deployable components**, all in this monorepo:
  - `backend/` — Spring Boot 3.5 (Java 21), Gradle Kotlin DSL, single-module
  - `android/` — Native Android (Kotlin 2.0, Jetpack Compose, Material 3),
    multi-module, includes a Wear OS app
  - `web/` — Next.js 15 App Router (TypeScript strict, Tailwind v4, pnpm)
- **Shared state**: backend owns Cloud Firestore (native mode). Both clients
  read from the backend, not directly from Firestore.
- **Hosting**: backend and web both deploy to Cloud Run in `us-central1`.

## Where to find things
- Architecture overview (start here): [`docs/architecture.md`](docs/architecture.md)
- Durable reference (data model, API surface, cross-cutting patterns, feature
  catalog): [`docs/reference/`](docs/reference/)
- Product & non-functional requirements, incl. the PHI **privacy & compliance**
  posture: [`docs/requirements/`](docs/requirements/)
- Architecture Decision Records: [`docs/decisions/`](docs/decisions/)
- Forward-looking plans (work not yet built): [`docs/plans/`](docs/plans/)
- Implemented/superseded specs & plans (historical): [`docs/archive/`](docs/archive/)
  — all `IMPL-*` specs were archived 2026-09; there is no `docs/specs/` anymore
- Per-component guidance: `backend/CLAUDE.md`, `android/CLAUDE.md`,
  `web/CLAUDE.md` override this file inside their respective directories.

## Conventions
- Conventional Commits (`feat:`, `fix:`, `chore(scope):`, etc.)
- Trunk-based dev on `main`. Feature branches named `feature/<slug>` (see
  Worktrees below for the exact form).
- One commit per logical change. Don't squash unrelated work.
- **Android image loads don't share the API auth stack.** Coil's singleton
  `ImageLoader` (AppModule) uses its own OkHttp client — the Retrofit
  `AuthInterceptor`/bearer does NOT apply. Loading from an authenticated backend
  endpoint (e.g. the SEC-012 private meal-photo redirect) requires a host-scoped
  **network** interceptor that adds the bearer only on backend-host hops, so it
  isn't sent on a cross-host 302 to a GCS signed URL (which rejects requests
  carrying `Authorization`). See `ImageAuthInterceptor`.
- **A new persistence-backed bean breaks every `@SpringBootTest` until you stub
  it.** Backend tests run with `firestore-enabled: false`, so the Firestore
  repository impls (`@ConditionalOnProperty`) don't load — `TestPersistenceConfig`
  supplies in-memory/stub beans instead. Adding a new *mandatory* bean (e.g. a
  service) that depends on a NEW core repository interface fails context startup
  for the ENTIRE suite until you add an in-memory bean for that repository to
  `TestPersistenceConfig`.
- **New mutating endpoints must be replay-safe and classified.** Every
  POST/PUT/PATCH/DELETE is enforced by `WriteContractTest`: add it to
  `backend/src/test/resources/write-contract.txt` (`CATEGORY|METHOD path`) or the
  build fails. An offline client replays mutations, so a create must be idempotent
  (client-minted id or `SyncWriteContext.idempotentCreate`), never `NEEDS_FIX`.
  Rationale: `docs/reference/write-contract.md`.
- **New synced Firestore collections must be routed on every client.** The
  backend's delta-emitted collection set is pinned by a shared fixture: add the
  collection to `docs/reference/sync-emitted-collections.txt` (or
  `SyncEmittedCollectionsContractTest` fails) AND to Android's `CollectionRegistry`
  (or `CollectionRegistryContractTest` fails). Skipping the registry silently
  drops cross-device changes — the bug class this contract closes. **Also bump
  Android `SyncEngine.MIRROR_SCHEMA_VERSION`**: existing installs sync by an
  incremental cursor, so a newly-routed collection's rows created *before* that
  cursor stay orphaned and never appear (a pre-support build skipped them and
  advanced the cursor past them). The bump makes `ensureState` reset the cursor
  once for a backfilling full scan. This is client-local — do NOT bump
  `SYNC_SCHEMA_VERSION` (the wire/server D13 version) for this, or every pull
  mismatches the server and wipe-loops.
- **Android Room schema bumps fail loud.** Bumping `HfDatabase` version requires a
  `Migration` in `ALL_MIGRATIONS` (+ bump `SCHEMA_VERSION`) and a fixture
  round-trip test; a missing forward migration now THROWS at open instead of
  destructively wiping the outbox/drafts (only pre-outbox schemas 1–2 may still
  wipe). `HfDatabaseMigrationCoverageTest` gates the chain.
- **Offline-safe web writes go through the outbox.** Client mutations that must
  survive a flaky network use `web/lib/offline/writes` (→ IndexedDB outbox →
  `/api/outbox/replay`), not a bare server action, and apply optimistic UI. A new
  replayable endpoint must be added to `web/lib/offline/replay-endpoints.ts` (the
  authenticated-proxy allowlist) or the outbox refuses to enqueue it.

## Worktrees
- Worktrees live in `.worktrees/` at the repo root (not `.claude/worktrees/`).
- Directory name = short slug (e.g. `build_fixes`); branch name = full
  `feature/<slug>` (e.g. `feature/build_fixes`).
- Branch from `origin/main`:
  `git worktree add -b feature/<slug> .worktrees/<slug> origin/main`
- Android Gradle builds require the web OAuth client id (injected from a secret
  in CI). Locally / in a fresh worktree pass a stub to compile/test without it:
  `-PwebOauthClientId=000000.apps.googleusercontent.com`. Also recreate the
  gitignored `local.properties` (`sdk.dir=<android-sdk path>`) per worktree.

## AI Models
- **General AI work** (text generation, parsing, extraction, lookup):
  `gemini-3.8-flash`
- **Image generation**: `gemini-3.1-flash-image` (GA; exercise-media generation
  overrides to the higher-quality `gemini-3-pro-image` in prod)
- **Documented Gemini Pro exceptions** (each sanctioned by an ADR): Goals chat
  (`gemini-3.1-pro-preview`, ADR-0005) and the workout-program designer
  (`gemini-3.1-pro-preview`, ADR-0013).
- Prefer **GA model ids over `-preview`**: preview ids carry earliest-shutdown
  dates and have expired unnoticed in prod (SOTA-002). Verify the current GA id
  before pinning a model.
- Don't introduce another Gemini model, or another provider (OpenAI, Anthropic,
  etc.), without an ADR.
- **Video / large media to Gemini go through the Files API**, not inline bytes:
  `client.files.upload(...)` → poll until `state == ACTIVE` → reference via
  `Part.fromUri(uri, mime)` (see `GeminiFilesService` / `EquipmentVideoDetector`).
  The app is API-key mode (not Vertex), so `gs://` direct input isn't available —
  upload the bytes. Set `mediaResolution = MEDIA_RESOLUTION_LOW` to cut video
  token cost (~100 tok/s vs ~300 at default). Small images still go inline via
  `Part.fromBytes`.
- **Multi-turn Gemini tool loops must preserve `thought_signature`.** Gemini 3
  thinking models attach a `thought_signature` to each `functionCall` Part; when
  you send the model's tool-call turn back (model turn + your `functionResponse`)
  echo those Parts **verbatim** — capture them from `chunk.parts()`, don't rebuild
  with `Part.fromFunctionCall(name, args)`, which drops the signature and 400s
  (`Function call is missing a thought_signature in functionCall parts`). Only
  agentic loops (tool call → feed result → re-invoke) hit this; single-shot
  terminal tool calls don't. See `GeminiWorkoutProgramChatClient`.

## Never
- Commit secrets, service account JSON keys, OAuth client secrets, or
  `local.properties`.
- Edit files in `.github/workflows/` without calling it out in the PR description.
- Introduce a new Gemini model or AI provider without an ADR (the sanctioned
  Pro exceptions today are ADR-0005 and ADR-0013).

## Deploys
- Prod deploy = merge to `main`. Cloud Build triggers (`deploy-backend-on-main`,
  `deploy-web-on-main`, `release-android-on-main`) fire on merge and are
  **path-filtered** — a component with no changed files no-ops (shows
  `neutral`/`skipped`). To redeploy an unchanged component, run its trigger
  manually.
- **The Android release ships via Firebase App Distribution — it is NOT
  auto-installed.** `release-android-on-main` builds the R8 release and uploads it
  to Firebase App Distribution (CI holds `roles/firebaseappdistro.admin`); a
  tester must manually update through the App Tester app. Unlike the backend/web
  Cloud Run deploys (live the moment the merge deploy finishes), an Android change
  reaches no device until the build is installed — rule this in/out first when a
  client change "isn't showing up", but don't stop there if the tester confirms
  they're on the new build.
- **`main` is protected by the `main-protection` ruleset** (since 2026-09-14):
  changes land via PR and six CI checks (`android-ci`, `backend-ci`, `web-ci`,
  `terraform-ci`, both CodeQL `analyze`) must be green to merge; admins keep a
  break-glass bypass. Prod deploy = merge to `main`, so CI now gates deploys at
  the merge. The Cloud Build deploy still runs post-merge and can fail
  independently — watch the `deploy-*-on-main` check-runs on the merge commit.
- **CI workflows run only on PRs targeting `main`.** A stacked PR (base =
  another feature branch) gets NO checks until it targets `main`; retarget its
  base (or close/reopen) to trigger CI before relying on it.
- The backend deploy runs a **Trivy image scan** at deploy time
  (`--severity=HIGH,CRITICAL --ignore-unfixed --exit-code=1`) that blocks
  promotion on any *fixable* HIGH/CRITICAL CVE. Fix by overriding the
  BOM-managed transitive version via an `extra["<lib>.version"]` property in
  `backend/build.gradle.kts` (see the existing block there). This runs only at
  deploy time, not in PR CI, so it can silently keep prod on a stale revision.
  When the only fixed version is a **major** upgrade (e.g. a Spring Framework 7
  fix for a Boot 3.x app) so the `extra[...]` override can't apply, suppress the
  finding in the repo-root **`.trivyignore`** (wired via `--ignorefile=.trivyignore`
  on the `scan-image` step) — ONLY for a provably non-reachable CVE, and each entry
  must carry a dated justification + a removal condition. The scan step runs with
  `cwd=/workspace`, so the file lives at the repo root, not under `backend/`.
- **Cloud Run Jobs reuse the backend image, which is tuned for 2Gi.** The image
  `ENTRYPOINT` hardcodes `-XX:MaxRAMPercentage=65.0` (`backend/Dockerfile`), so
  any Cloud Run surface running it — service *or* job — must be provisioned
  `--memory=2Gi`. Cloud Run Jobs default to **512Mi**, at which the JVM is
  OOM-killed mid-Spring-boot before the runner executes (no app logs, trips the
  `cloud_run_job_errors` alert). Every `infra/scripts/deploy-*job*.sh` must pass
  `--memory=2Gi`; a new job script that omits it fails silently on every run.

## Local Development
Run `bash infra/scripts/dev.sh` to start both backend and web servers locally.
This script:
- Fetches all required secrets from GCP Secret Manager
- Creates `web/.env.local` with `BACKEND_URL`, auth secrets, etc.
- Starts backend on http://localhost:8090 and web on http://localhost:3000
  (the backend is also served over HTTPS on :8443 via Tailscale for device testing)
- Ctrl-C stops both servers

**web/pnpm:** `package.json` pins `pnpm@10.32.1`; under corepack a different
local pnpm refuses to run. Match it, run tooling via the local binaries
(`web/node_modules/.bin/{next,eslint,tsc}`), or pass `--pm-on-fail=ignore`.

## Audits & archives
- `docs/audit/<date>/` — dated audit runs. `findings.json` is the machine index
  (stable finding IDs like `SEC-012`, `CICD-001`); `INDEX.md` is the human entry
  point; `reaping-plan.md` classifies doc artifacts for archival.
- `docs/archive/<yyyy-mm>/` — superseded/implemented specs, plans, and other
  artifacts live here with an `ARCHIVED` header (date, classification, evidence,
  successor). **Never delete** archived docs; never treat them as current.
- Before creating a spec/plan doc, check the audit's reaping plan conventions.

## Tools
- GCP project: `health-fitness-160`
- Region: `us-central1`
- Firestore **prod database is the *named* `production`** (not `(default)`);
  local dev runs against `(default)`. Anything that names a DB must scope it via
  `FIRESTORE_DATABASE_ID` / `var.firestore_database` — hard-coding `(default)`
  silently writes to the wrong database.
- **Operating GCP headless / from an agent:** interactive `gcloud auth login`
  isn't available, so `gcloud auth print-access-token` (user creds) fails — but
  **ADC works**. Use it for REST (`Authorization: Bearer $(gcloud auth
  application-default print-access-token)` + `x-goog-user-project:
  health-fitness-160`) and for `tofu`/`terraform` (GCS state backend + google
  provider both read ADC). To run a `gcloud` command with ADC:
  `export CLOUDSDK_AUTH_ACCESS_TOKEN=$(gcloud auth application-default print-access-token)`.
- Gotcha: GNU `timeout` is NOT installed — don't wrap gcloud/curl in it; it
  fails with 'command not found' and looks like an auth/API failure.
- **Reading a failed Cloud Build's logs headless:** `gcloud builds log <id>`
  needs user creds (fails 'Reauthentication') — export
  `CLOUDSDK_AUTH_ACCESS_TOKEN=$(gcloud auth application-default print-access-token)`
  first. The Cloud Logging `entries:list` server filter on
  `resource.labels.build_id` returns 0 for build logs; filter by
  `logName=".../logs/cloudbuild"` + a tight `timestamp` window and post-filter by
  build_id client-side. The Trivy result is in the `scan-image` step lines.
- `AGENTS.md` (formerly a never-filled placeholder for the Google Health API
  Parity Tool context file) was archived 2026-09 to
  `docs/archive/2026-09/AGENTS.md` — there is no `AGENTS.md` at the repo root.
