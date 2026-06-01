# tesseta

A three-component health & fitness platform: a native Android app (phone +
Wear OS), a Next.js web app, and a Spring Boot backend. The **backend is the
source of truth** and owns the only connection to Cloud Firestore. Health data
flows in from Fitbit hardware via the Google Health API into the backend, then
out to both clients over REST + SSE.

This codebase is built and maintained by **Claude Code**. Start with the docs
below before changing anything.

## Documentation map

| Read this | For |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | System shape, data flow, per-component overview — **start here** |
| [`docs/reference/data-model.md`](docs/reference/data-model.md) | Firestore collections & record shapes |
| [`docs/reference/api-surface.md`](docs/reference/api-surface.md) | Every REST/SSE endpoint by domain |
| [`docs/reference/patterns.md`](docs/reference/patterns.md) | Auth, ingestion, Goals metric engine, Gemini, streaming, caching |
| [`docs/reference/feature-catalog.md`](docs/reference/feature-catalog.md) | What's shipped / deferred / fixture per platform |
| [`docs/decisions/`](docs/decisions/) | Architecture Decision Records |
| `backend/CLAUDE.md`, `web/CLAUDE.md`, `android/CLAUDE.md` | Per-component conventions |

## Feature set

Auth (Google ID tokens across web/phone/wear), profile & unit preferences,
Google Health ingestion, dashboard, blood markers + lab-PDF extraction, body
composition + DEXA, medications + adherence, AI-planned Goals, nutrition
tracking + capture, and gyms/equipment. Admin surfaces (drug & equipment
catalogs) are web-only. See the
[feature catalog](docs/reference/feature-catalog.md) for the full state,
including deferred work (workout logging, Wear OS surfaces).

## Quickstart

The fastest path runs both servers with secrets pulled from GCP:

```bash
bash infra/scripts/dev.sh   # backend :8080 + web :3000, Ctrl-C stops both
```

Or per component:

```bash
# Backend (Spring Boot, Java 21)
cd backend && ./gradlew :app:bootRun

# Web (Next.js 15, Node 22, pnpm)
cd web && pnpm install && pnpm dev

# Android phone / Wear OS debug
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :wear:assembleDebug
```

GCP one-time provisioning lives under [`infra/`](infra/). See its README for the
setup sequence.

## License

TBD. See [`LICENSE`](LICENSE).
