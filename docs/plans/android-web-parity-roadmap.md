# Android ↔ Web Feature Parity Roadmap

**Date:** 2026-05-26
**Updated:** 2026-06-02
**Status:** Mostly shipped — foundations (§3) + IMPL-AND-01..06 + IMPL-AND-12 (goals) + IMPL-13 (testosterone marker, nutrition) have landed; Android phone is now at near-parity with web. Remaining work is active workout logging (Phase 7, needs ADR), Wear OS surfaces (Phase 8), a few Phase 9 stretch items (sleep, push notifications, dark mode), and a new cross-cutting **offline-first sync** workstream (IMPL-AND-20 / ADR-0007, planned). Original discovery survey from 2026-05-26 preserved below with status indicators brought current.

This document compares the current state of the **web** (Next.js 15) and **Android** (Compose) clients, identifies the gap, and proposes a phased roadmap to bring Android to feature parity. It began as a living survey, not an authoritative spec — individual workstreams were split into IMPL specs under `docs/specs/` before implementation. The status section below (§0) is the at-a-glance source of truth; the domain-by-domain and phase sections that follow retain the original discovery analysis with statuses updated.

---

## 0. Implementation Status (2026-05-31)

At-a-glance truth. Foundations and all phone domains through Goals have shipped; Nutrition shipped ahead of roadmap order. Remaining work is workout logging, Wear OS surfaces, and a few stretch items.

### Foundations (§3)

| Item | Status | Note |
|---|---|---|
| §3.1 Hilt DI | ✅ Shipped | Application annotated, `@HiltViewModel` across ~30 ViewModels |
| §3.2 Navigation | ✅ Shipped | Single NavHost (string routes, not typed `Routes`) + nested per-feature graphs + phone "More" hub |
| §3.3 Network | ✅ Shipped | Retrofit + OkHttp + Moshi in `core-data/net`; AuthInterceptor + 401 silent-refresh; 12 API services |
| §3.4 SSE consumer | ✅ Shipped | `core-data/net/Sse.kt`; drug lookup, goal chat, blood + DEXA upload |
| §3.5 Room | ✅ Activated | IMPL-AND-20 landed an encrypted Room+SQLCipher store (`hf-offline.db`) as the on-device source of truth for all in-scope domains; wiped on sign-out. DataStore still holds prefs/token |
| §3.6 Coil | ✅ Shipped | coil-compose 2.7.0 |
| §3.7 File picker + multipart | ✅ Shipped | `MultipartUploadClient`; blood/DEXA PDF, gym cover photo, nutrition capture |
| §3.8 ViewModels | ✅ Shipped | Convention adopted, ~30 `@HiltViewModel` screens |
| §3.9 Error/loading/empty states | ✅ Shipped | Shared `LoadingState`/`ErrorState`/`EmptyState` in core-ui |
| §3.10 PDF viewing | ✅ Shipped | System-intent approach chosen (FileProvider + `ACTION_VIEW`), not embedded |

### Domains / Phases

| Phase / Spec | Domain | Status | Note |
|---|---|---|---|
| Phase 0 | Foundations | ✅ Shipped | See above |
| Phase 1 / IMPL-AND-01 | Dashboard | ✅ Shipped | Live weight/body-comp hero, blood panel, today's doses; HRV/RHR/readiness + recent feed remain fixture on both clients |
| Phase 2 / IMPL-AND-02 | Settings & Profile | ✅ Shipped | Profile, Google Health connect/disconnect, sign out, **Unit Preferences** (lb/kg, in/cm, °F/°C) app-wide |
| Phase 3 / IMPL-AND-03 | Medications | ✅ Shipped | Streaming drug lookup, scheduling, adherence, edit/discontinue, dated dosage periods (PR#8) |
| Phase 4 / IMPL-AND-04 | Blood | ✅ Shipped | Reports, markers, manual add, lab-PDF upload (SSE), report detail, PDF via system intent; +IMPL-13 TESTOSTERONE marker |
| Phase 5 / IMPL-AND-05 | Body Composition & DEXA | ✅ Shipped | Hero + 90d trend + deltas, scan grid, upload (SSE), editable detail, delete |
| Phase 6 / IMPL-AND-06 | Gym & Equipment | ✅ Shipped | List/create/edit, cover-photo upload, equipment + spec schema, per-location overrides; bulk CSV + image regen deferred |
| Phase 6.5 / IMPL-AND-12 | Goals | ✅ Shipped | List, roadmap detail, create/edit, AI goal-chat (SSE) → proposal → commit; `feature-goals` module |
| Phase 9 (out of order) / IMPL-13 | Nutrition | ✅ Shipped | Today/Target/Capture (barcode + meal/label photo), Add Food; dashboard calories/macros card |
| Phase 7 | Active workout logging | ⏳ Remaining | Not built; `WorkoutSessionService` is a commented-out manifest stub; needs its own ADR |
| Phase 8 | Wear OS surfaces | ⏳ Remaining | Only sign-in/token relay works; no glance/Tiles/Complications/Health Services |
| Phase 9 (remainder) | Stretch | ⏳ Remaining | Sleep tracking, push/dose reminders, dark mode still open (Nutrition + Unit prefs done) |
| Cross-cutting / IMPL-AND-20 | Offline-first sync | ✅ Built (pending device E2E) | Encrypted Room store as UI source of truth, background sync engine (delta pull + outbox + LWW), backend soft-delete/tombstone contract, unified `/api/me/sync` API, idempotent client-UUID writes, FCM silent-push fan-out. Decision: ADR-0007. All 8 phases implemented & unit/MockMvc-green; instrumented + live-FCM E2E authored but need a device/emulator + deployed run (see plan status table + outstanding-questions). A few documented fast-follows remain (offline adherence logging, goal nested-aggregate offline, per-row badge wiring, idempotency on the long tail of write endpoints) |
| §2.8 | Admin | ⏳ Deferred | Intentionally deferred to desktop |

### Empty modules (intentional / forward-looking)

- `feature-chat` — **empty.** Standalone "AI Coach" chat not built. (Goal-chat lives in `feature-goals`; `core-chat` has a reusable `ChatSseClient`/`ChatModels`/`ChatThread` but no general-coach app surface consumes it.)
- `core-health` — **empty.** On-device Health Connect (`androidx.health.connect`) not done; body composition comes from the backend's Google Health sync, not the device.

---

## 1. Executive Summary

### Current state

| Surface | Status | Notes |
|---|---|---|
| **Web** | Feature-rich, production-grade | All major domains implemented end-to-end: dashboard, blood, body comp / DEXA, medications, gym + equipment catalog, goals, nutrition, plus full admin suite (equipment review, drug catalog). Backed by real backend endpoints, SSE streaming for PDF/AI work, Google Health OAuth. |
| **Android (phone)** | Near-parity (~90%) | Foundations shipped (Hilt, NavHost, Retrofit/SSE/multipart, Coil, shared state primitives). Dashboard, settings/profile + unit prefs, medications, blood, body comp/DEXA, gym/equipment, goals, and nutrition are all wired to the real backend across ~30 `@HiltViewModel` screens. Remaining gaps: active workout logging (greenfield, needs ADR), admin (deferred), and a few stretch items. `feature-chat` and `core-health` remain empty by design. |
| **Android (wear)** | ~30% complete | Token sync from phone working (sign-in, token cache, sync service, sign-in-required screen). No "Today" glance, Tiles, Complications, or Health Services implementation yet. |

### Headline gap (resolved)

The original headline gap — Android had **no live backend integration** — is closed. The network layer (Retrofit + OkHttp + Moshi in `core-data/net` with an AuthInterceptor and 401 silent-refresh), an SSE consumer, multipart upload, and ~30 ViewModels now back the app; the former `DashboardFixtures.kt` hardcoded numbers are gone except where data is genuinely fixture on **both** clients (HRV/RHR/readiness and the recent-activity feed — the backend has no source for these yet). The remaining gap is **active workout logging** (greenfield on both clients, awaiting an ADR), **Wear OS surfaces**, and a short tail of Phase 9 stretch items.

### How it landed

1. **Foundation first** — Hilt, NavHost, API client, SSE/multipart, Coil, and shared state primitives landed in Phase 0 before feature work, as planned. Room was kept incremental (backend stays source of truth; DataStore holds prefs/token) rather than pre-built.
2. **Domain priority** — the web's most-used domains shipped in order: dashboard wiring → settings/profile → medications → blood → body composition → gym/equipment → goals, with nutrition delivered ahead of schedule.
3. **Wear OS as a tail** — wear-specific surfaces (Tiles, Complications, Health Services) remain deferred. Token relay is in place; the glance/tile/complication work is still pending.

---

## 2. Domain-by-Domain Gap Analysis

### 2.1 Authentication

| Capability | Web | Android | Gap |
|---|---|---|---|
| Google OAuth sign-in | ✅ Auth.js + Google provider | ✅ Credential Manager + Web client ID | None |
| Session persistence | ✅ JWT cookie | ✅ DataStore (`IdTokenCache`) | None |
| Token refresh | ✅ Auto-refresh < 60s before expiry | ✅ `silentRefresh()` on bootstrap | None |
| Google Health scope (incremental) | ✅ `/me/profile` consent flow with `access_type=offline` | ✅ Done — `GoogleHealthScopeRepository`, `GoogleHealthScopes`, `GoogleHealthService` + `GoogleHealthViewModel` | None |
| Admin gating | ✅ `isAdmin()` check in layout | ❌ No admin surfaces at all (deferred) | Intentionally deferred — no mobile admin |
| Wear OS sign-in | n/a | ✅ Token relayed from phone | None |

**Verdict:** ✅ Sign-in is solid and the **Google Health incremental scope flow shipped** (connect/disconnect from Settings). Only remaining item is admin role detection, which is intentionally deferred since no admin surfaces are planned for mobile.

---

### 2.2 Dashboard / Home

| Capability | Web | Android | Gap |
|---|---|---|---|
| Layout | Server-rendered cards in 920px container | ✅ Responsive Compose (phone vs foldable) | Android layout is arguably nicer; no gap |
| Stat cards (weight, HRV, RHR, readiness) | ✅ UI + sparklines (HRV/RHR/readiness are **fixtures** on web too) | ⚠️ UI + sparklines; weight live, HRV/RHR/readiness fixture | HRV/RHR/readiness remain fixture on **both** clients — backend has no source yet |
| Weight chart (90d + 7d MA) | ✅ Real data | ✅ Live — wired via `DashboardViewModel` | None |
| Blood panel (top 4 markers) | ✅ Real data with reference ranges | ✅ Live — top markers from backend | None |
| Today's doses card | ✅ Real data, "take" action | ✅ Live — wired to medications API | None |
| Recent activity feed | ⚠️ Fixture on web too | ⚠️ Fixture on Android | Fixture on **both**; out of scope until backend aggregates events |
| Body composition hero | ✅ Real data | ✅ Live — weight/body-comp hero | None |

**Verdict:** ✅ Shipped (IMPL-AND-01). `DashboardViewModel` drives per-card states; the weight/body-comp hero, blood panel, and today's doses card are all wired to the real backend. HRV/RHR/readiness and the recent-activity feed remain fixture on **both** clients pending a backend source.

---

### 2.3 Blood Testing

| Capability | Web | Android | Gap |
|---|---|---|---|
| List recent reports | ✅ | ✅ Overview screen | None |
| Add manual marker reading | ✅ (`AddReadingButton`) | ✅ Manual add-reading form | None |
| Upload lab PDF | ✅ SSE-streamed extraction | ✅ Multipart upload + SSE | None |
| Marker history (sparklines) | ✅ Per-marker last 12mo | ✅ Marker detail history | None |
| Reference ranges | ✅ Visual bars | ✅ Range bars/sparklines on tracked markers | None |
| PDF download / view | ✅ Proxied PDF | ✅ System intent (FileProvider + `ACTION_VIEW`) | None |
| Report detail (extracted markers list) | ✅ `ExpandableReport` | ✅ Report detail screen | None |

**Verdict:** ✅ Shipped (IMPL-AND-04). Overview, marker detail history, manual add, lab-PDF upload via SSE, report detail, and system-intent PDF view all built; UI primitives (`BloodPanel`, `RangeBar`) reused. IMPL-13 additionally added **TESTOSTERONE** as a canonical marker (verified live on device).

---

### 2.4 Body Composition & DEXA

| Capability | Web | Android | Gap |
|---|---|---|---|
| Weight + body fat tracking | ✅ Google Health sync | ✅ Via backend's Google Health sync | None (on-device Health Connect intentionally not used) |
| Manual weight entry | ⚠️ Indirect (via Google Health) | ⚠️ Indirect (via Google Health) | Parity — neither client has a direct entry form |
| DEXA scan list | ✅ Grid view | ✅ Scan grid | None |
| DEXA scan upload (PDF) | ✅ SSE extraction | ✅ Multipart + SSE | None |
| DEXA detail (editable regions) | ✅ Click-to-edit per region | ✅ Click-to-edit regional values | None |
| DEXA delete | ✅ | ✅ Delete | None |
| 90d weight chart | ✅ | ✅ Live 90d trend | None |
| 7d / 90d weight deltas | ✅ | ✅ Computed deltas | None |

**Verdict:** ✅ Shipped (IMPL-AND-05). Overview hero + 90d weight trend + deltas, DEXA scan grid, DEXA upload via SSE, DEXA detail with click-to-edit regional values, and delete are all built. Body composition data comes from the **backend's** Google Health sync, not on-device Health Connect.

---

### 2.5 Medications

| Capability | Web | Android | Gap |
|---|---|---|---|
| Active medications grid | ✅ `MedicationGrid` | ✅ List screen | None |
| Drug AI lookup (streaming) | ✅ SSE LLM lookup | ✅ SSE drug lookup (`DrugLookupStreamClient`) | None |
| Add medication form | ✅ Drug search → dose/frequency/time slots | ✅ Add via streaming lookup | None |
| Frequency types (daily/weekly/monthly/cycle/PRN) | ✅ | ✅ Frequency + time-slot scheduling | None |
| Time slot scheduling (morning/afternoon/evening/bedtime) | ✅ | ✅ Time-slot scheduling | None |
| Adherence logging | ✅ Per-day taken/not-taken | ✅ Today's doses + adherence logging | None |
| Adherence sparkline (30d) | ✅ | ✅ | None |
| Discontinue with reason | ✅ | ✅ Edit/discontinue | None |
| Drug image (AI generated) | ✅ Displayed in card | ✅ Via Coil | None |
| Dated dosage periods | ✅ (PR#8 contract) | ✅ Dated dosage periods | None |
| Marker correlation linking | ✅ Optional | ⏳ Deferred | Optional, deferred |
| Protocol linking | ✅ Optional | ⏳ Deferred | Optional, deferred |

**Verdict:** ✅ Shipped (IMPL-AND-03). List, add via streaming drug AI lookup (SSE), frequency + time-slot scheduling, today's doses + adherence logging, edit/discontinue, and dated dosage periods (PR#8 contract) all built; drug images via Coil. Optional marker-correlation and protocol linking remain deferred.

---

### 2.6 Workouts & Gym Tracking

| Capability | Web | Android | Gap |
|---|---|---|---|
| List user gyms | ✅ `LocationCard` grid | ✅ Gym list | None |
| Create gym | ✅ Name, address, hours, amenities | ✅ Create gym | None |
| Edit gym | ✅ | ✅ Edit gym | None |
| Cover photo upload | ✅ | ✅ Multipart cover-photo upload | None |
| Gym detail view | ✅ With equipment table | ✅ Gym detail with equipment | None |
| Set default gym | ✅ | ✅ | None |
| Delete gym | ✅ | ✅ | None |
| Equipment catalog (browse) | ✅ Per gym | ✅ | None |
| Add equipment | ✅ With spec schema per category | ✅ Spec schema per category | None |
| Per-location equipment spec overrides | ✅ | ✅ Per-location overrides | None |
| Bulk equipment import (CSV preview) | ✅ | ⏳ Deferred | Desktop-friendlier (as planned) |
| Equipment image (AI generated) | ✅ Displayed in cards | ✅ Via Coil | None |
| Equipment image regeneration | ✅ | ⏳ Deferred | Admin-flavored (as planned) |
| **Active workout logging** | ❌ Not built on web either | ❌ Not built | Greenfield — needs own ADR; `WorkoutSessionService` is a commented-out manifest stub |
| **Workout history** | ❌ Not built on web | ❌ Not built | Greenfield |
| Wear OS workout session | ❌ n/a | ❌ Not built | Tied to greenfield workout logging |

**Verdict:** ✅ Shipped to web parity minus admin-flavored bits (IMPL-AND-06). Gym list, create/edit with cover-photo multipart upload, gym detail with equipment, add equipment (spec schema per category), and per-location overrides all built. Bulk CSV import and equipment image regeneration remain deferred as planned. **Active workout tracking is still undefined on both sides** (Phase 7) — Android is the natural home (sensors, Health Connect, Wear), and it still needs a separate ADR before scoping.

---

### 2.7 Profile & Settings

| Capability | Web | Android | Gap |
|---|---|---|---|
| Display name / email | ✅ Read-only | ✅ Profile screen | None |
| Height input | ✅ | ✅ Form field | None |
| Google Health connection status | ✅ With disconnect/reconnect | ✅ Connect/disconnect | None |
| Sign out | ✅ via Auth.js | ✅ Sign-out button | None |
| Theme / dark mode | ❌ Not yet | ❌ Not yet | Future (Phase 9) |
| Notifications settings | ❌ | ❌ | Future (Phase 9) |
| Unit preferences (lb/kg, in/cm) | ❌ Hard-coded in components | ✅ Unit Preferences (lb/kg, in/cm, °F/°C) applied app-wide | Android now ahead of web here |

**Verdict:** ✅ Shipped (IMPL-AND-02). Settings screen (reached from the "More" hub), Profile (name/email/height), Google Health connect/disconnect, and sign out all built. A new **Unit Preferences** feature (lb/kg, in/cm, °F/°C) applied app-wide resolves the long-standing cross-cutting units item — Android is now ahead of web on unit handling. Dark mode and notifications remain Phase 9 stretch items.

---

### 2.7b Goals

| Capability | Web | Android | Gap |
|---|---|---|---|
| Goal list (cards + phase progress) | ✅ `/me/goals` | ✅ Goal list | None |
| Goal detail roadmap (phases/steps) | ✅ `GoalDeepResponse` | ✅ Roadmap detail (phases/steps/metric chips) | None |
| Metric-bound steps + auto-evaluation | ✅ server-evaluated | ✅ Evaluated status displayed | None |
| AI goal-chat (SSE) → editable proposal → commit | ✅ | ✅ SSE goal-chat → proposal → commit | None |
| Manual goal create / edit / delete / reorder | ✅ | ✅ Manual create/edit | None |

**Verdict:** ✅ Shipped (IMPL-AND-12). Goal list, roadmap detail
(phases/steps/metric chips/evaluated status), manual create/edit, and AI
goal-chat (SSE) → proposal → commit are all built in a dedicated
`feature-goals` module with its own nav graph. Goal-chat reuses the SSE
consumer from IMPL-AND-03; metric bindings read the Meds/Blood/Body-comp
models, which all landed first as planned.

---

### 2.8 Admin (Equipment + Drug Catalogs)

| Capability | Web | Android | Gap |
|---|---|---|---|
| Pending equipment review | ✅ Approve/reject/edit/merge | ❌ | Admin section |
| Active equipment catalog mgmt | ✅ | ❌ | Admin section |
| Drug catalog mgmt | ✅ Edit/regen image/merge/delete | ❌ | Admin section |
| Image regeneration with custom prompt | ✅ | ❌ | Modal flow |

**Verdict:** ⏳ Deferred (as planned). No admin surfaces on mobile. Admin features remain **low priority for mobile** — most admin work is faster on desktop with multi-pane editing and drag-drop. Still **deferred indefinitely** unless a clear admin-on-the-go use case emerges.

---

### 2.9 Wear OS

| Capability | Web | Android Wear | Gap |
|---|---|---|---|
| Sign-in handoff from phone | n/a | ✅ Working (MainActivity, token cache, sync service, sign-in-required screen) | None |
| "Today" glance screen | n/a | ❌ Not built | Build minimal vitals tile |
| Active workout session (HR, duration, sets) | n/a | ❌ Not built | Greenfield — tie to workout logging |
| Tiles | n/a | ❌ Dep wired, no impl | Build after phone domains stable |
| Complications | n/a | ❌ Dep wired, no impl | Build last (lowest impact) |
| Health Services integration | n/a | ❌ Dep wired, no impl | Required for active workouts |

**Verdict:** ⏳ Remaining (Phase 8). Only sign-in/token relay works today. The "Today" glance, Tiles, Complications, and Health Services work is still pending — appropriate now that the phone domains have shipped.

---

## 3. Foundational / Architectural Changes

These are prerequisites for almost any domain work. They should be sequenced **before** feature IMPLs, or at minimum the first feature IMPL should land them.

### 3.1 Dependency injection (Hilt)
- **Status:** ✅ Done. Hilt integrated — `Application` annotated, `@HiltViewModel` across ~30 ViewModels, manual graph wiring replaced.
- **Action (done):** Annotate `Application`, wire `@HiltViewModel`, replace manual graph wiring in `AppRoot`
- **Blocks:** All ViewModels, all repositories
- **Sizing:** ~1 cycle, mechanical refactor

### 3.2 Navigation graph
- **Status:** ✅ Done. Single `NavHost` in `app` with **string-route** destinations (not a typed `Routes` object) and nested per-feature graphs (medications, blood, body-composition, workouts, settings, goals, nutrition) plus a phone "More" hub. Bottom-nav and foldable sidebar dispatch into it.
- **Action (done):** Build single `NavHost` with destinations for every top-level screen; migrate bottom-nav and foldable sidebar to dispatch into it
- **Blocks:** Every screen beyond the dashboard
- **Sizing:** ~1 cycle once Hilt lands

### 3.3 Network layer (Retrofit + OkHttp + Moshi)
- **Status:** ✅ Done. Retrofit + OkHttp + Moshi live in `core-data/net` (no separate `core-network` module — `core-data` was extended instead). An `AuthInterceptor` injects the bearer token and a `TokenAuthenticator` performs 401 silent-refresh. **12 Retrofit API services** exist (dashboard, medications, goals, chat, blood, body-composition/dexa, workouts location + equipment, nutrition x3), mirroring web's per-domain `lib/*-api.ts` pattern.
- **Action (done):**
  - OkHttp interceptor that injects `Authorization: Bearer {idToken}`
  - Auto-refresh on 401 via the token authenticator
  - Moshi adapters for DTOs
  - Retrofit services per domain
- **Blocks:** All live data
- **Sizing:** ~1–1.5 cycles

### 3.4 SSE / streaming consumer
- **Status:** ✅ Done. `core-data/net/Sse.kt`. Consumed by drug lookup (`DrugLookupStreamClient`), goal chat (`ChatRepository`/`ChatApi`), blood report upload, and DEXA upload.
- **Action (done):** Custom SSE reader for blood upload, DEXA upload, drug lookup, goal chat
- **Blocks:** Lab PDF upload, DEXA upload, AI drug lookup, goal chat
- **Sizing:** ~3 days inside the first IMPL that needs it

### 3.5 Local persistence (Room)
- **Status:** ⏳ Incremental, by design. Room was deliberately **not** made central — the backend remains the source of truth, and DataStore handles prefs/token. Build entities + DAOs only as a given domain needs them.
- **Action:** Build entities + DAOs only as needed per domain (don't pre-build for unused features). Pattern: backend is source of truth, Room caches for offline read.
- **Blocks:** Offline support
- **Sizing:** Incremental per domain

### 3.6 Image loading (Coil)
- **Status:** ✅ Done. coil-compose 2.7.0 present, wired to backend image URLs (drug images, equipment images, gym cover photos).
- **Action (done):** Add Coil 2.x, wire to backend image URLs
- **Blocks:** Medications grid, equipment cards, gym detail
- **Sizing:** ~1 day

### 3.7 File picker + multipart upload
- **Status:** ✅ Done. `MultipartUploadClient` handles file picking + multipart upload. Used by blood PDF, DEXA PDF, gym cover photo, and nutrition capture.
- **Action (done):** File picker for PDFs/images, multipart Retrofit request
- **Blocks:** Blood upload, DEXA upload, gym cover photo upload, nutrition capture
- **Sizing:** ~2 days

### 3.8 ViewModels for every screen
- **Status:** ✅ Done. Convention adopted — ~30 `@HiltViewModel` screens, each exposing a `StateFlow<UiState>`.
- **Action (done):** Adopt convention as each domain lands. Pattern: `@HiltViewModel` exposes `StateFlow<UiState>` per screen.
- **Sizing:** Per-domain

### 3.9 Error handling / toasts / loading states
- **Status:** ✅ Done. Shared `LoadingState` / `ErrorState` / `EmptyState` primitives live in `core-ui` and are used across features.
- **Action (done):** Standard loading / empty / error states for lists; surface auth errors centrally
- **Sizing:** ~3 days, ideally as part of the first real feature

### 3.10 PDF viewing
- **Status:** ✅ Done. **System-intent approach chosen** (not embedded): blood/DEXA reports share the downloaded PDF to the system viewer via `FileProvider` + `ACTION_VIEW`.
- **Action (done):** Hand off to system viewer via `Intent`
- **Blocks:** Blood report viewing, DEXA PDF download
- **Decision:** Resolved — system intent over embedded `androidx.pdf`.

---

## 4. Proposed Phased Roadmap

> Sizing: each "phase" is roughly one focused implementation cycle. Adjust as scope clarifies.

### ✅ DONE — Phase 0 — Foundations (blocking everything else)
1. Hilt integration (§3.1)
2. NavHost + route every existing screen + placeholder screens (§3.2)
3. Retrofit + auth interceptor + base URL config (§3.3)
4. Coil for image loading (§3.6)
5. Snackbar / loading / error state primitives (§3.9)

**Outcome:** Skeleton ready to grow. No new user-visible features yet.

### ✅ DONE — Phase 1 — Wire the existing dashboard to real data (IMPL-AND-01)
1. `/api/me/profile` for identity, height, Google Health status
2. `/api/me/body-composition` for dashboard weight chart, hero card
3. `/api/me/blood/markers` for dashboard blood panel (top 4 markers + ranges)
4. Replace `DashboardFixtures.kt` usages

**Outcome:** First time the dashboard shows real data on Android.

### ✅ DONE — Phase 2 — Settings & Profile (IMPL-AND-02)
1. Settings screen (More tab destination)
2. Profile sub-screen: name, email, height
3. Google Health connection: status, connect (incremental OAuth), disconnect
4. Sign out button

**Outcome:** Account management parity with web. Additionally shipped app-wide **Unit Preferences** (lb/kg, in/cm, °F/°C), resolving the cross-cutting units item.

### ✅ DONE — Phase 3 — Medications (highest daily-use feature) (IMPL-AND-03)
1. Medications list screen
2. Drug AI lookup (SSE) → add medication form
3. Frequency + time slot scheduling
4. Today's doses card on dashboard (replace `TodayCard` med fixture)
5. Adherence logging (quick-log tile already in mockup)
6. Adherence sparkline (30d)
7. Edit / discontinue medication

**Outcome:** Med tracking parity. Likely two IMPL specs.

### ✅ DONE — Phase 4 — Blood Testing (IMPL-AND-04)
1. Blood reports list
2. Lab PDF upload via SSE
3. Manual marker reading form
4. Marker detail with history sparkline
5. In-app or system PDF viewer

**Outcome:** Blood parity. PDF viewing used the system-intent approach. IMPL-13 also added the **TESTOSTERONE** canonical marker.

### ✅ DONE — Phase 5 — Body Composition & DEXA (IMPL-AND-05)
1. Body composition screen (weight/fat over time, full chart, deltas)
2. DEXA scan list
3. DEXA upload via SSE
4. DEXA detail with editable regions
5. DEXA delete

**Outcome:** Body comp parity.

### ✅ DONE — Phase 6 — Gym & Equipment (IMPL-AND-06)
1. Gym list
2. Create / edit gym (form + cover photo upload)
3. Gym detail with equipment table
4. Add equipment (spec schema per category)
5. Per-location equipment spec overrides
6. (Defer: bulk import — desktop-friendlier)
7. (Defer: equipment image regeneration — admin task)

**Outcome:** Gym parity (minus admin-flavored features).

### ✅ DONE — Phase 6.5 — Goals (IMPL-AND-12)

Goals + phases + steps + metric bindings + AI goal-chat, to parity with
web's IMPL-12 (`/me/goals`). Numbered **IMPL-AND-12** to match the
backend/web spec (`IMPL-12-goals-module.md`) rather than the AND-0X
sequence. Sequence after Medications/Blood/Body-comp (its metric bindings
read those domains' models) and before/alongside Workouts.

1. Goal list + detail (roadmap timeline, metric chips, evaluated status)
2. Manual create/edit + reorder (optimistic)
3. AI goal-chat (SSE) → editable proposal card → commit
4. `feature-goals` module + nav destination

**Outcome:** Goals parity with web. Spec: `docs/specs/IMPL-AND-12-goals-module.md`.

### ⏳ REMAINING — Phase 7 — Greenfield: Active workout logging
*Not built. `WorkoutSessionService` is still a commented-out manifest stub. Requires its own ADR before scoping. Likely Android-first.*
1. Define workout data model (exercises, sets, reps, weight, duration, HR)
2. Active workout foreground service (`WorkoutSessionService`)
3. Wear companion screen: HR, duration, current set
4. Health Connect integration for HR / steps
5. Workout history screen
6. Backend endpoints (new on backend too)

### ⏳ REMAINING — Phase 8 — Wear OS surfaces
*Not built. Only phone↔wear sign-in/token relay works today.*
1. Wear "today glance" with vitals
2. Wear Tile (vitals at-a-glance)
3. Wear Complication (today's dose count, etc.)

### Phase 9 — Stretch / nice-to-have
1. ✅ DONE — Nutrition logging (greenfield, IMPL-13) — shipped ahead of order. NutritionToday/Target/Capture (barcode scan + meal/label photo via multipart), Food/Nutrition/NutritionCapture APIs, AddFood; dashboard "Today" calories/macros card wired.
2. ⏳ Sleep tracking via Health Connect — remaining.
3. ⏳ Push notifications (dose reminders, etc.) — remaining.
4. ⏳ Theme / dark mode — remaining.
5. ✅ DONE — Unit preferences (lb/kg, in/cm, °F/°C) — shipped app-wide with Phase 2; Android is now ahead of web here.

---

## 5. Cross-Cutting Changes

These apply across multiple phases but don't slot cleanly into one.

| Change | Status | Why |
|---|---|---|
| **Establish ViewModel convention** | ✅ Done | Set in Phase 1, applied consistently across ~30 `@HiltViewModel` screens. |
| **Define DTO ↔ domain model boundary** | ✅ Done | Mirrored in `core-domain` + `core-data`. |
| **Standardize "edit in place" UX** | ✅ Done | Used in DEXA detail (click-to-edit regional values). |
| **Multipart upload helper** | ✅ Done | `MultipartUploadClient`, reused across blood / DEXA / gym photo / nutrition. |
| **SSE consumer** | ✅ Done | `core-data/net/Sse.kt`, reused across blood / DEXA / drug lookup / goal chat. |
| **Reference-range bar component** | ✅ Done | `BloodPanel.RangeBar` reused on blood overview + dashboard. |
| **Sparkline component** | ✅ Done | Reused for adherence, marker history, dashboard vitals. |
| **Admin role detection** | ⏳ Deferred | No admin surfaces on mobile; deferred as planned. |
| **Unit handling (kg/lb, cm/in, °F/°C)** | ✅ Done | Centralized Unit Preferences applied app-wide; Android now ahead of web. |
| **Build flavors for env (dev/staging/prod)** | ✅ Done | Backend URL configurable; network layer in `core-data/net`. |
| **System-bars insets / back affordances / predictive back** | ✅ Done | Edge-to-edge insets on all screens, on-screen back on every pushed screen, predictive back enabled (`android:enableOnBackInvokedCallback=true`); phone "More" hub directory. Documented in `android/CLAUDE.md`. |

---

## 6. Explicit Non-Goals

To prevent scope creep, the following are **out of scope** for parity (unless reprioritized):

- **Admin equipment review / catalog / drug management on mobile** — desktop is the right form factor.
- **Bulk CSV imports** — desktop-friendlier.
- **Image regeneration UI with custom prompts** — admin tool.
- **Multi-language support** — not present on web.
- **Tablet-specific layouts beyond what `FoldableDashboardScreen` already does** — out of scope.
- **Native iOS** — not in this monorepo.

---

## 7. Open Questions

1. **(OPEN) Active workout logging design.** Still greenfield on both sides — not built. Where does the workout data model live? Does the backend store it, or does Android own it locally until uploaded? Needs an ADR before Phase 7.
2. **(OPEN) Offline support strategy.** Room stayed incremental and the backend remains the source of truth (DataStore holds prefs/token). The question of how aggressive offline read/write caching should be is still open.
3. **(OPEN) Push notifications.** Dose reminders are still unbuilt (Phase 9 remainder) — a likely high-value feature with no web equivalent.
4. **(RESOLVED) PDF viewing approach.** Chose the **system-intent** approach (`FileProvider` + `ACTION_VIEW`) over embedded `androidx.pdf`. Used for blood + DEXA reports.
5. **(OPEN, leaning no) Admin on mobile.** Still deferred indefinitely — no admin surfaces shipped. A quick approve/reject could be a small future addition if a use case emerges.
6. **(OPEN) Wear standalone use cases.** Workout-in-progress is the obvious one and is unbuilt; other wrist surfaces (e.g. logging a dose) remain undecided.
7. **(OPEN) AI Coach / `feature-chat` module.** `feature-chat` is still **empty** — a standalone AI Coach chat was not built. (Note: goal-chat *was* built, but it lives in `feature-goals`; `core-chat` holds a reusable `ChatSseClient`/`ChatModels`/`ChatThread` that no general-coach surface yet consumes.) Whether a standalone coach is in the roadmap, and its design, are still open.
8. **(RESOLVED) Unit handling (kg/lb, cm/in, °F/°C).** Centralized **Unit Preferences** shipped app-wide (Phase 2), applied across features. Android is now ahead of web on this.

---

## 8. Suggested Next Steps

Status as of 2026-05-31 — foundations and Phases 1–6.5 (plus Nutrition) have shipped. What remains:

1. ✅ Done — Carved Phase 0 + dashboard into IMPL specs; foundations and IMPL-AND-01..06 + IMPL-AND-12 + IMPL-13 all landed.
2. ✅ Done — PDF viewer approach decided (system intent) and `android/CLAUDE.md` updated (Hilt + NavHost shipped).
3. ✅ Done — IMPL-AND-12 (Goals) sequenced after Meds/Blood/Body-comp as planned; goal-chat reuses the IMPL-AND-03 SSE consumer.
4. ⏳ **Open an ADR for active workout logging** before Phase 7 work begins (`WorkoutSessionService` is a commented-out stub).
5. ⏳ **Scope Wear OS surfaces** (Phase 8) — glance, Tiles, Complications, Health Services — now that phone domains are stable.
6. ⏳ **Decide the remaining Phase 9 stretch items** — sleep tracking (Health Connect), push/dose reminders, dark mode.
7. ⏳ **Decide whether the standalone AI Coach** (`feature-chat`, currently empty) is in or out; `core-chat` already has reusable SSE chat primitives.
