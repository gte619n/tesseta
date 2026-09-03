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
- Open implementation specs (only those with remaining work): [`docs/specs/`](docs/specs/)
- Per-component guidance: `backend/CLAUDE.md`, `android/CLAUDE.md`,
  `web/CLAUDE.md` override this file inside their respective directories.

## Conventions
- Conventional Commits (`feat:`, `fix:`, `chore(scope):`, etc.)
- Trunk-based dev on `main`. Feature branches named `feature/<slug>` (see
  Worktrees below for the exact form).
- One commit per logical change. Don't squash unrelated work.

## Worktrees
- Worktrees live in `.worktrees/` at the repo root (not `.claude/worktrees/`).
- Directory name = short slug (e.g. `build_fixes`); branch name = full
  `feature/<slug>` (e.g. `feature/build_fixes`).
- Branch from `origin/main`:
  `git worktree add -b feature/<slug> .worktrees/<slug> origin/main`

## AI Models
- **General AI work** (text generation, parsing, extraction, lookup):
  `gemini-3.8-flash`
- **Image generation**: `gemini-3.1-flash-image-preview`
- **Documented Gemini Pro exceptions** (each sanctioned by an ADR): Goals chat
  (`gemini-3.1-pro-preview`, ADR-0005) and the workout-program designer
  (`gemini-3.1-pro-preview`, ADR-0013).
- Don't introduce another Gemini model, or another provider (OpenAI, Anthropic,
  etc.), without an ADR.

## Never
- Commit secrets, service account JSON keys, OAuth client secrets, or
  `local.properties`.
- Edit files in `.github/workflows/` without calling it out in the PR description.
- Introduce a new Gemini model or AI provider without an ADR (the sanctioned
  Pro exceptions today are ADR-0005 and ADR-0013).

## Local Development
Run `bash infra/scripts/dev.sh` to start both backend and web servers locally.
This script:
- Fetches all required secrets from GCP Secret Manager
- Creates `web/.env.local` with `BACKEND_URL`, auth secrets, etc.
- Starts backend on http://localhost:8080 and web on http://localhost:3000
- Ctrl-C stops both servers

## Tools
- GCP project: `health-fitness-160`
- Region: `us-central1`
- `AGENTS.md` at the repo root is a **placeholder** for the Google Health API
  Parity Tool context file — it has not been added yet, so don't rely on it.
