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
| Medications | ✅ + adherence | ✅ `/me/meds` | ✅ feature-medical | Drug lookup via SSE; dosage periods; adherence |
| Goals | ✅ metric-event engine + Cloud Run Job | ✅ `/me/goals*` | ✅ feature-goals | AI goal chat (Gemini Pro, SSE) → proposal → commit |
| Nutrition | ✅ logs + capture | ✅ `/me/nutrition*` | ✅ feature-nutrition | Capture via Gemini meal/label/barcode; **not** SSE |
| Gym & equipment | ✅ + bulk import | ✅ `/me/workouts/gyms*` | ✅ feature-workouts | Bulk CSV import preview/confirm; cover-photo upload |
| Admin (drugs, equipment) | ✅ `/api/admin/**` | ✅ `/admin/**` | ➖ no mobile admin | Email-gated; intentionally web-only |
| Workout logging | ➖ `Workout` scaffold only | ➖ | ➖ | Deferred — needs a data-model ADR |
| Wear OS surfaces | n/a | n/a | ➖ sign-in only | Tiles/complications/Health Services deferred |

## Dashboard (the one partial)

The dashboard data layer is live on both clients; some tiles are still fixtures
on **both** because the backend has no source for them yet.

- **Live:** weight / body-composition hero + chart, blood panel (top markers),
  today's doses, identity. Steps and sleep are live on the daily-vitals tiles.
- **Fixture (both clients):** HRV, Resting HR, and Readiness vital tiles; the
  recent-activity feed; the date/time/timezone header. On web these come from
  `web/lib/fixtures/dashboard.ts`; on Android they are gated by
  `DashboardFlags` in `dashboard/Fallbacks.kt`
  (`showVitalsFixtures` / `showRecentFeedFixtures` / `showTodayCardFixtures`).

Android dashboard screens (`PhoneTodayScreen`, `FoldableDashboardScreen`) **do**
consume `DashboardViewModel` — the remaining work (IMPL-AND-01) is replacing
those fixtures once a backend source exists, not wiring the screens. The
tracking spec [`../specs/IMPL-AND-01-dashboard-live-data.md`](../specs/IMPL-AND-01-dashboard-live-data.md)
is retained because of this open work.

## Known placeholders / cleanups

- Android `core-data` declares a **Room** dependency that is entirely unused —
  the app is backend-driven (Retrofit + OkHttp disk cache; DataStore only for
  the token cache and unit prefs). Treat Room as a dead dependency.
- Settings "About" links use placeholder `https://placeholder.tesseta.app/`
  URLs.
- `isAdmin()` allow-lists are hardcoded on both web and backend (TODO: move to
  env/DB).
