# Feature catalog

What is built, on which platform, and what is intentionally deferred or still a
fixture. This is the durable replacement for the per-feature `IMPL-*` specs.
For the forward-looking parity plan (Phases 7–9), see
[`../plans/android-web-parity-roadmap.md`](../plans/android-web-parity-roadmap.md).

Legend: ✅ shipped · ◐ partial · ⚠️ fixture/placeholder · ➖ not built (deferred).

| Feature | Backend | Web | Android | Notes |
|---|---|---|---|---|
| Auth / sign-in | ✅ JWT resource server | ✅ Auth.js + Google | ✅ Credential Manager | Wear = token relay from phone only |
| Profile & units | ✅ `PATCH /me` | ✅ `/me/profile` | ✅ feature-settings | Unit prefs (lb/kg, in/cm, °F/°C) app-wide |
| Google Health connect | ✅ webhook + KMS | ✅ connect/status | ✅ scope flow | Ingestion is backend-side; clients read normalized data |
| Dashboard | ✅ data endpoints | ✅ `/` Suspense-streamed | ◐ live VM, some fixtures | See "Dashboard" below |
| Blood | ✅ readings + report extraction | ✅ `/me/blood` | ✅ feature-blood | PDF upload via multipart+SSE |
| Body composition / DEXA | ✅ | ✅ `/me/body-composition` | ✅ feature-body-composition | DEXA upload via multipart+SSE; editable regions |
| Medications | ✅ + adherence + reminder settings | ✅ `/me/meds` | ✅ feature-medical | Drug lookup via SSE; dosage periods; adherence; dose reminders (IMPL-16) — Android-local alarms + grouped check-off notifications, config at `reminder-settings` |
| Goals | ✅ metric-event engine + Cloud Run Job | ✅ `/me/goals*` | ✅ feature-goals | AI goal chat (Gemini Pro, SSE) → proposal → commit |
| Nutrition | ✅ logs + capture + describe + recents/relog | ✅ `/me/nutrition*` | ✅ feature-nutrition | Capture via Gemini meal/label/barcode (background upload on Android); fire-and-forget describe (202 placeholder) on both clients; unified add surface w/ time-inferred meal chip + one-tap recents; calories always derived from macros (4/4/9); exact branded-product recognition (IMPL-16); **not** SSE |
| Gym & equipment | ✅ + bulk import | ✅ `/me/workouts/gyms*` | ✅ feature-workouts | Bulk CSV import preview/confirm; cover-photo upload |
| Admin (drugs, equipment) | ✅ `/api/admin/**` | ✅ `/admin/**` | ➖ no mobile admin | Email-gated; intentionally web-only |
| Workout programs | ✅ programs + materialized sessions | ✅ `/me/workouts/programs*` | ✅ feature-workouts | Periodized program model (IMPL-15). Android Workouts hub is a tabbed shell defaulting to a "This Week" view (`ThisWeekStrip` + compliance calendar) |
| Exercise catalog & demos | ✅ catalog + authoring/review pipeline + dynamic demo frames (IMPL-14/19) | ✅ `/admin/exercises*` authoring | ◐ inline demo viewer | No standalone Android library screen — the START/MID/END (or dynamic 1–N frame) demo is an inline `ExerciseDetailSheet` inside program/session screens. Owner-only "flag demo frame" pulls a bad frame back into review |
| Workout logging | ✅ session completion + actuals fan-out (IMPL-17) | ✅ log-result modal | ✅ full-screen set-by-set coach | LoggedSets feed weekly aggregates + goal metrics (ADR-0012). Android coach: spoken TTS cues, editable sets, prior-session prefill, **mid-session exercise swap** (gym-available substitutes), offline-first completion; paged/phase-delineated workout history (online-only) |
| Program designer (AI) | ✅ history-grounded Gemini-Pro chat (IMPL-18) | ✅ weights + "why" + nutrition strip + TRT panel | ✅ IMPL-AND-18 chat | Grounded in logged/imported history (e1RM, ease-in), volume/ramp/deload guardrails, per-phase nutrition, grounded TRT decision-support (ADR-0015) folded into the chat |
| Wear OS surfaces | n/a | n/a | ➖ sign-in only | Tiles/complications/Health Services deferred (the `health.services.client` dep is declared but unused) |

## Dashboard (the one partial)

The dashboard data layer is live on both clients; almost every tile is now
backed by a real backend source. The lone remaining gap is **Readiness**.

- **Live (both clients):** weight / body-composition hero + chart, blood panel
  (top markers), today's doses, identity, steps, sleep, **HRV**, **Resting HR**
  (all from `GET /api/me/daily-metrics`), and the **recent-activity feed**
  (`GET /api/me/recent-activity`). Web builds vitals in `web/lib/dashboard-vitals.ts`
  and the feed in `web/lib/recent-feed.ts`; both render an em-dash empty state
  (not fabricated numbers) when the backend has no series. Android reads the same
  endpoints via `DashboardViewModel`.
- **No backend source yet:** the **Readiness** vital tile (Android falls back to
  a `DashboardFallbacks` placeholder; there is no web Readiness tile). The
  date/time/timezone header is a client-rendered value.

There is **no longer a `web/lib/fixtures/dashboard.ts`** — that file was removed
when the vitals/feed went live. On Android the old `DashboardFlags` constants
`showVitalsFixtures` and `showTodayCardFixtures` are now **dead** (defined but
read nowhere); only `showRecentFeedFixtures` was ever a live gate and it is now
`false`. The tracking spec
[`../specs/IMPL-AND-01-dashboard-live-data.md`](../specs/IMPL-AND-01-dashboard-live-data.md)
is retained only for the Readiness gap.

## Known placeholders / cleanups

- Android `core-data` uses **Room + SQLCipher** as the offline-first read mirror
  (ADR-0007) — a real `@Database` (`data.db.HfDatabase`) driving the `SyncEngine`
  + outbox. It is NOT a dead dependency. (DataStore holds only the token cache
  and unit prefs; a handful of capture/search/reference repos still read
  Retrofit-direct — see `android/CLAUDE.md`.)
- Settings "About" links use placeholder `https://placeholder.tesseta.app/`
  URLs.
- `isAdmin()` allow-lists are hardcoded on both web and backend (TODO: move to
  env/DB).
