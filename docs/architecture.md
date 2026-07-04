# Architecture

This is the entry point for understanding the system. It gives the shape of the
whole; the [reference docs](#reference) carry the durable detail, and each
component's `CLAUDE.md` carries its day-to-day conventions.

## The three components

One monorepo, three deployables. **The backend is the source of truth** and
owns the only connection to Cloud Firestore (native mode). Clients read
normalized data from the backend's REST API — never from Firestore directly.

| Component | Stack | Deploy |
|---|---|---|
| `backend/` | Spring Boot 3.5, Java 21 (virtual threads), Gradle Kotlin DSL, single-module | Cloud Run, `us-central1` |
| `web/` | Next.js 15 App Router, TypeScript strict, Tailwind v4, pnpm | Cloud Run, `us-central1` |
| `android/` | Kotlin 2.0, Jetpack Compose, Material 3, multi-module (+ Wear OS) | Play (phone + wear share `applicationId`) |

Why this split (full rationale in [ADR-0001](decisions/ADR-0001-three-component-architecture.md)):
native Android buys first-class Health Connect / Wear OS / foldable support;
Next.js gives SSR for research browsing and a clean home for LLM chat surfaces;
Cloud Run gives both services identical CI/CD and scale-to-zero.

## Data flow

```
Fitbit device ──▶ Google Health API ──▶ backend webhook ──▶ Firestore
                                              │                  │
                                     (hydrate via REST)   (source of truth)
                                                                 │
                                   ┌─────────────────────────────┤
                                   ▼                             ▼
                              web (Next.js)               android (phone + wear)
                              REST + SSE                  REST + SSE
```

Health data flows **in** through a webhook receiver
(`integrations/webhook`), which hydrates the full record via the Google Health
API client (`integrations/googlehealth`) and writes normalized records to
Firestore. Both clients read that data **out** through the backend's REST
surface. LLM features stream over SSE. See
[patterns.md → Google Health ingestion](reference/patterns.md#google-health-ingestion).

## Backend at a glance

**Single Gradle module** (base package `com.gte619n.healthfitness`); the layering
below is a **package convention**, not a build boundary (it used to be five
modules — see [`backend/CLAUDE.md`](../backend/CLAUDE.md) for why it was
collapsed). Packages are `<layer>.<feature>`:

```
api ─▶ core, integrations       persistence ─▶ core       integrations ─▶ core
core (domain records + pure-domain services + the goals event system)
```

Controllers (`api`) → services (`core`) → repository interfaces (`core`) →
Firestore implementations (`persistence`). DTOs and domain types are Java
**records**. No Lombok, no JPA/SQL. Synchronous-on-virtual-threads (no WebFlux).
Conventions in [`backend/CLAUDE.md`](../backend/CLAUDE.md).

## Web at a glance

App Router, **Server Components by default**; `"use client"` only for
state/events. Server-only `lib/*-api.ts` helpers wrap `apiFetch` (reads
`BACKEND_URL` + the Auth.js session); mutations are inline `"use server"`
actions passed down as props (the **server-action-as-prop** pattern). Tailwind
v4 tokens live in `app/globals.css` `@theme`; UI primitives are shadcn-style
copy-ins under `components/ui/`. Charts are hand-rolled SVG (no chart library).
Conventions — including the toast/confirm hooks and the modal-backdrop close
pattern — in [`web/CLAUDE.md`](../web/CLAUDE.md).

## Android at a glance

Multi-module Compose. **The backend is the source of truth**, and `core-data`
runs an **offline-first mirror** against it (ADR-0007): reads come from a local
**Room + SQLCipher** store kept fresh by a `SyncEngine`, writes are journaled to
an outbox and replayed. **DataStore** holds only small key/value state (the
ID-token cache and unit prefs). Networking is Retrofit + OkHttp (20 MB disk
cache). Hilt DI is fully wired; a single `NavHost` registers per-feature nav
graphs, with a phone-only "More" hub for parity features. `core-domain` holds
repository interfaces, `core-data` the mirror/Retrofit implementations + network
clients, `core-ui` the `Hf` design tokens and shared primitives. On-device
Health Connect is **not integrated** — health data arrives backend-side via the
Google Health API; a dedicated `core-health` module would house device-side
access if/when that lands. Wear shares `core-domain` and never depends on `app`.
Conventions in [`android/CLAUDE.md`](../android/CLAUDE.md).

## Reference

Durable detail lives in [`docs/reference/`](reference/):

- [**data-model.md**](reference/data-model.md) — Firestore collections, record
  shapes, blood markers, the `findLatest*` indexed-read pattern.
- [**api-surface.md**](reference/api-surface.md) — every REST/SSE endpoint by
  domain, with `[SSE]` / `[multipart]` flags.
- [**patterns.md**](reference/patterns.md) — auth, Google Health ingestion +
  KMS, the Goals metric-event engine, Gemini usage, SSE/multipart streaming,
  web data-fetching, backend caching.
- [**feature-catalog.md**](reference/feature-catalog.md) — what's shipped per
  platform, what's deferred, what's still a fixture.

## Requirements

Product & non-functional requirements — including the **privacy & compliance
posture** for this PHI-grade app — live in
[`docs/requirements/`](requirements/).

## Decisions & plans

- Architecture Decision Records: [`docs/decisions/`](decisions/).
- Forward-looking work not yet built: [`docs/plans/`](plans/) — the
  android↔web parity roadmap (Wear surfaces, sleep/push/dark-mode) and the
  bulk-equipment-import plan. (Active workout logging shipped since — ADR-0012;
  the roadmap doc itself is stale on that point.)

> Implementation specs (`IMPL-*`) that described already-shipped features have
> been retired; their durable content lives in the reference docs above. Only
> specs with open work remain under [`docs/specs/`](specs/).
