# State Management Robustness — Review & Plan

Status: proposed · Scope: Android app (primary), with notes for web · Related:
`ADR-0018-offline-first-read-pattern`, `IMPL-AND-20-offline-first-sync`,
`IMPL-STAB-android-stability-omnibus`.

## 1. Motivation

Two field-reported bugs, both rooted in the same class of problem — the same
piece of data has more than one reader and they are not all reactive to the one
source of truth:

1. **Medication reminder didn't appear in the morning; it popped up only when the
   app was opened.** Delivery problem, not a computation problem.
2. **Tapping "Take all" in the reminder notification cleared the notification but
   the home screen's "Today's doses" did not update.** Cross-screen staleness.

The reminders are the trigger; the underlying concern is that state management is
inconsistent across modules. This document diagnoses both bugs, inventories how
state is managed today, and proposes a phased plan to make it robust and
consistent.

## 2. Diagnosis

### Bug 2 — home screen didn't update after "Take all" (fixed here, Phase 0)

- The home card is `TodaysDosesCard` → `TodaysDosesViewModel`
  (`feature-medical/.../today/`). It reads `MedicationRepository.todaysDoses()`,
  which correctly overlays the local adherence mirror
  (`medicationAdherence` Room table) via `overlayMirroredAdherence`. So the source
  it reads is right.
- But it read that source **imperatively** — once on `init`, and again only on
  `LifecycleEventEffect(ON_RESUME)`. It did **not observe** the mirror.
- "Take all" runs in a background broadcast receiver (`ReminderActionReceiver` →
  `ReminderEngine.onDosesTaken` → `AdherenceRepository.logDose`) while the home
  screen is **already resumed**. Nothing re-triggered the read, so the card stayed
  stale. The reminder **notification** updated because `ReminderReplanCoordinator`
  genuinely observes the adherence mirror; the card did not.
- Aggravating factor: a second, disjoint reader exists —
  `DashboardTodaysDosesRepository.loadToday()` (`data/dashboard/DashboardData.kt`)
  hits `GET /api/me/medications/today` with **no** adherence overlay at all.

**Fix (Phase 0):** `TodaysDosesViewModel` now observes `LocalWriteBus` for the
`medicationAdherence` / `medications` tables and re-reads on any write — the same
invalidation the dashboard already uses. The card updates live from the reminder
action, in-app toggles, and doses synced from other devices.

### Bug 1 — morning reminder only appeared on app open (mitigated here, Phase 0)

Opening the app runs `ReminderEngine.replan()`, which posts the notification — so
the schedule computation is fine; background **delivery** failed. Ranked causes:

1. **Exact-alarm access off → windowed fallback batched by Doze (most likely).**
   `AndroidReminderScheduler.scheduleExact` uses `setExactAndAllowWhileIdle` only
   when `canScheduleExactAlarms()` is true; otherwise `setWindow(±15 min)`, which
   Doze can defer for hours overnight.
2. **App force-stopped / OEM battery-killed overnight.** AlarmManager alarms are
   cleared on force-stop and re-armed only on app open or reboot (the boot
   receiver is wired but fires only on real reboot). The ~12 h `ReminderPlanWorker`
   is the only background safety net.
3. Medication/settings changed elsewhere but not yet synced into the local mirror
   at alarm time.

**Fix (Phase 0):** declare `USE_EXACT_ALARM` (API 33+, granted at install, no user
prompt) so `canScheduleExactAlarms()` is always true and the scheduler always uses
`setExactAndAllowWhileIdle` — precise even in Doze. `SCHEDULE_EXACT_ALARM` still
covers API 31–32.

**Caveat (not solved by exact alarms):** a force-stop / aggressive OEM kill still
drops armed alarms with no reboot signal to re-arm. Full mitigation (a tighter
periodic `ReminderPlanWorker` cadence and/or a delivery-health banner) is proposed
in Phase 4. `USE_EXACT_ALARM` is a Play Console policy declaration; it is
permitted for alarm/reminder apps but must be declared in the listing.

## 3. Current-state inventory

The app already has the right primitives: Room mirror tables, a delta-pull
`SyncEngine` (LWW), an optimistic **outbox** (`MirrorRepositorySupport`),
`SyncSignals` (inbound FCM push hints), and `LocalWriteBus` (in-process
"something was written locally" invalidation hint). The problem is **uneven
adoption of a single source of truth (SSOT)**.

| Module | State source | Reactive to SSOT? | Refresh trigger |
| --- | --- | --- | --- |
| Medications list | Room mirror (`observe()`) | ✅ yes | reactive |
| Goals (list + roadmap) | Room mirror (`observeGoals` / `observeGoalDeep`) | ✅ yes | reactive + token |
| Workouts landing | Room mirror (`observeProgram` / calendar) | ✅ yes | reactive + token |
| Blood | Room mirror (`observeReadings/Reports`) | ✅ yes | reactive + TTL |
| Profile | Room mirror (singleton) | ✅ yes | reactive |
| **Today's doses (home + medical)** | REST projection + DataStore, overlaying adherence mirror | ❌ imperative (init/resume) | resume — **Bug 2** |
| **Dashboard cards** | per-card REST + DataStore snapshots | ❌ imperative | resume/TTL + `LocalWriteBus` |
| Nutrition today | REST reads + optimistic Room writes (hybrid) | ⚠️ partial | resume, FCM, settle-poll |

## 4. The core inconsistency

**A detail screen observes the Room mirror while the matching dashboard/home card
holds an independent REST/DataStore snapshot with no invalidation link** — so the
two show divergent values for the same underlying data. Confirmed instances:

- **Medications:** home "today's doses" (imperative) vs the adherence mirror the
  reminder engine and in-app toggles write — Bug 2.
- **Blood:** dashboard blood card (REST + DataStore) vs blood overview (Room
  mirror); refreshing one doesn't invalidate the other.
- **Workouts:** a parked-completion banner keyed by session id can orphan when the
  same session completes successfully elsewhere.
- **Goals:** step done/doneAt toggles are network-only (not optimistic), so two
  open views of the same goal lag until a re-fetch.
- **Dashboard:** each card is an independent cache with its own TTL; a single
  `LocalWriteBus` tick force-refreshes all cards, but server-derived cards
  (recent-activity) and TTL-gated cards can still momentarily disagree.

Root anti-pattern (same as the workout rest-timer fix on `workout-tweaks`): **two
independent states that must be kept in lockstep by hand.** The robust answer is
one observed source.

## 5. Best practices & libraries

This matches Google's official guidance — unidirectional data flow, **Room as the
single source of truth**, reactive repositories exposing `Flow`, ViewModels
exposing `StateFlow` via `stateIn(WhileSubscribed)`, and screens collecting with
`collectAsStateWithLifecycle` (the "Now in Android" reference architecture, and
this repo's own `ADR-0018` / `IMPL-AND-20`).

**Recommendation: do not add a new state library.** Store5 (Dropbox) and Molecule
(Cash App) solve problems this codebase already solves with its mirror + outbox +
`SyncEngine`; adopting one broadly would be a parallel system, not a
simplification. The fix is to **converge the imperative stragglers onto the
reactive Room-SSOT pattern the good modules already use** — the primitives are all
present.

## 6. Target principles

1. **One source of truth per domain = the Room mirror.** Every screen observes it;
   no screen holds a private fetched snapshot of shared data.
2. **Server-derived projections stay reactive** by overlaying the relevant mirror
   Flow (e.g. today's doses = server projection ⊗ adherence mirror), so a local
   write recomputes them without a network round-trip.
3. **ViewModels expose `StateFlow` via `stateIn(WhileSubscribed(5s))`**, derived
   from repository Flows; `refresh()` becomes a background revalidation, never the
   thing that makes data appear.
4. **`ON_RESUME`/pull-to-refresh are revalidation only** — kept only where data is
   genuinely non-mirrored/server-derived.
5. **DataStore snapshot caches are removed** where a Room mirror exists.

## 7. Phased plan

- **Phase 0 — motivating bug fixes (this branch, `reminder-fixes`).**
  - Today's doses reactive off `LocalWriteBus` (Bug 2). ✅
  - `USE_EXACT_ALARM` for precise background delivery (Bug 1). ✅
- **Phase 1 — unify "today's doses" into one reactive read. ✅ (this branch)**
  `MedicationRepository.observeTodaysDoses()` is now a single reactive source —
  `combine(TodaysDosesCache.observe(today), adherenceDao.observeAll())` overlaid —
  and `TodaysDosesViewModel` collects it (with `refreshTodaysDoses()` as background
  revalidation). Every observer of the rendered card (home, foldable, full screen)
  updates live on any dose write. The vestigial, never-rendered
  `DashboardViewModel.todaysDoses` / `DashboardTodaysDosesRepository` (overlay-less
  REST) is removed in Phase 2, alongside the other dashboard cache cleanup and its
  ViewModel test / sign-out wiring.
- **Phase 2 — dashboard invalidation + dead-source removal. ✅ (this branch)**
  Chosen the lighter, low-risk path (the plan's "make `LocalWriteBus` invalidation
  domain-complete") over a full DataStore→Room rewrite of the most-viewed screen:
  the dashboard already force-refreshed all cards on any local write; it now also
  refreshes on `SyncSignals` pushes, so a change on another device (or any
  server-side change) invalidates the imperative cards instead of sitting stale
  until the next resume/TTL. Deleted the never-rendered, overlay-less
  `DashboardTodaysDosesRepository` / `DashboardDosesCache` / `TodaysDoseDto` /
  `TodaysDoseSummary` / `DoseWindow` / the dashboard `todaysDoses` card state and
  its sign-out wiring — the disjoint source the plan flagged. Deeper per-card
  reactive-mirror observation (blood/body-composition/daily-metrics reading the
  Room mirror directly, eliminating their DataStore snapshots) remains an optional
  future refinement; the local + push invalidation now covers the actual
  staleness bug class.
- **Phase 3 — standardize ViewModel state. ✅ (audit: already conformant)**
  A full sweep of every ViewModel and screen found the target architecture is
  already in place:
  - **Lifecycle-safe collection everywhere** — no bare `collectAsState()` in any
    feature or app screen; all use `collectAsStateWithLifecycle`.
  - **Immutable state exposure everywhere** — all 44 ViewModels expose
    `StateFlow` (via `asStateFlow()`/`stateIn`); no public `MutableStateFlow`.
  - **Reactive Room-SSOT repos** back every mirror-backed domain (medications,
    goals, workouts, blood, profile, and — from Phase 1 — today's doses).
  Deliberate decision: the remaining `ON_RESUME`/pull refreshes were **kept**, not
  stripped. They revalidate genuinely server-derived data (nutrition REST day,
  the dashboard's aggregate cards, the today's-doses server projection) or guard
  cross-device freshness where a foreground sync-pull isn't guaranteed. Removing
  them would be change-for-its-own-sake with a staleness risk — the opposite of
  the plan's goal. Mechanically rewriting the already-reactive `combine`/
  `flatMapLatest` ViewModels (workouts landing, goals list) into terminal
  `stateIn` was likewise skipped: it's non-mechanical (multi-source merges with
  loading/error handling) and pure churn on working screens for no user benefit.
- **Phase 4 — durability & delivery. ◑ (partial — this branch)**
  - **Reminder delivery-health. ✅** Tightened the periodic `ReminderPlanWorker`
    from 12 h → **1 h** and switched its registration to `UPDATE` so existing
    installs adopt it. Exact alarms (Phase 0's `USE_EXACT_ALARM`) don't survive a
    force-stop / OEM battery-kill, and the boot receiver only fires on a real
    reboot — so a dropped morning alarm previously wouldn't re-arm until the app
    was opened. The hourly `replan()` (cheap, offline-safe) now self-heals that
    within the hour.
  - **Deferred (need careful design, not a quick sweep):**
    - *Workouts parked-completion auto-cleanup.* The optimistic completion marks
      the scheduled mirror row `COMPLETED` **before** its upload parks on a 4xx, so
      a naive "hide the banner when the row is COMPLETED" check would suppress
      legitimate recovery banners and **lose the user's logged sets**. A correct
      fix must key off the row being SYNCED/server-reconciled (not the optimistic
      write) — high-stakes, so left for a dedicated change with tests.
    - *Goals step-done optimism.* Step done/doneAt is a deliberately
      server-evaluated mutation; making it optimistic risks diverging from the
      server's evaluation. Needs its own design pass.
    - *Nutrition mirror-as-SSOT* and a *cross-screen consistency test harness*
      remain as follow-ups.

## 8. Risks / caveats

- `USE_EXACT_ALARM` requires the Play Console policy declaration.
- Exact alarms do not survive force-stop / aggressive OEM kills — Phase 4.
- Triggering `todaysDoses()` on every adherence write incurs a network fetch until
  Phase 1 makes the reactive read cache-overlay only; acceptable and matches the
  dashboard's existing behavior.
