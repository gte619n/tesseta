# Offline-first launch — decision log (branch `offline-fix`)

Context: the APK still showed loaders / login flashes on every launch despite an
offline-first core (Room mirror + outbox + delta sync). This log records the
non-obvious decisions and any issues encountered while fixing it, for review.

## Goal
Whenever the app opens it should immediately show the most-recent synced offline
data. A loader is acceptable ONLY on the very first sign-in (no cache yet). No
login flashes.

## Root cause summary
1. **Auth `Loading` frame rendered the SignInScreen.** `AuthCoordinator` starts in
   `AuthState.Loading`; `bootstrap()` runs async and its first act is a DataStore
   read. During that gap `AppRoot` painted `SignInScreen` -> login flash.
2. **"Setting up…" shown on every launch.** Deciding whether a first sync is
   needed required opening the SQLCipher DB (`firstSyncGate.get()` ->
   `sync_state.lastFullSyncAt`). Until that finished, `gating == null` shared a
   screen with `gating == true`, so returning users saw the first-run copy.
3. **Dashboard cards were network-first.** Today's doses + nutrition hit the
   network with no cache; body-comp/blood/metrics awaited a network fill before
   serving Room.
4. **Per-screen VMs reset to `Loading` on every entry** (medications).

## Decisions
- (D1) Use `androidx.core:core-splashscreen` and hold the system splash until the
  AuthCoordinator resolves out of `Loading`. The system splash (app icon on the
  brand canvas) covers the auth gap instead of the login screen, and also removes
  the white theme flash.
- (D2) Persist a cheap `first_sync_complete` boolean in the **auth DataStore**
  (already opened during bootstrap; no SQLCipher). `SignedInRoot` reads it to
  decide gating instantly, off the encrypted-DB critical path. The encrypted DB
  then builds lazily in the background. `FirstSyncGate` writes the flag true once
  the initial windowed pull completes (and treats a pre-existing
  `lastFullSyncAt` as already-complete for users upgrading into this build).
- (D3) Warm `AuthCoordinator.bootstrap()` from `HealthFitnessApp.onCreate` so the
  cached session is usually resolved before first composition.
- (D4) Dashboard repos serve cached/mirror data first and revalidate in the
  background; today's doses + nutrition get a single-slot DataStore cache so they
  render last-known instantly instead of always spinning on the network.
- (D5) Medications list goes reactive off the Room mirror (no `Loading` reset on
  every screen entry).

## What changed (by file)
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — add
  `androidx.core:core-splashscreen`.
- `app/src/main/res/values/themes.xml` + `colors.xml` — `Theme.HealthFitness` now
  derives from `Theme.SplashScreen` (brand-canvas background, launcher-foreground
  icon) with `postSplashScreenTheme = Theme.HealthFitness.Main`.
- `MainActivity` — `installSplashScreen()` + `setKeepOnScreenCondition` holds the
  splash while `AuthState.Loading` (capped at `MAX_SPLASH_HOLD_MS = 1500`). The
  launch-time `bootstrap()` call is removed (now in the Application). `AuthState.Loading`
  renders nothing (splash covers it) instead of `SignInScreen`. `SignedInRoot` now
  decides gating from the cheap `isFirstSyncComplete()` flag; only a genuine first
  run opens the SQLCipher DB on the visible path and shows `SettingUpScreen`.
- `AuthCoordinator` — `bootstrapOnce()` (idempotent launch bootstrap) +
  `isFirstSyncComplete()` delegate.
- `IdTokenCache` — `first_sync_complete` flag (read/write); cleared on sign-out.
- `FirstSyncGate` — injects `IdTokenCache`; `markFirstSyncComplete()`.
- `HealthFitnessApp` — kicks `authCoordinator.bootstrapOnce()` first in `onCreate`.
- Dashboard: `Dashboard.kt` interfaces gain `cached*()`; `DashboardData.kt` impls
  read Room/cache-only for the seed; new `DashboardDosesCache` (DataStore) for the
  doses card; `NutritionRepository.cachedDay()`; `DashboardViewModel` seeds each
  card from cache (no spinner) then revalidates and keeps cache on failure;
  `SignOutSideEffects` clears the doses cache.
- Medications: `MedicationRepository.observe()` (reactive Room mirror) + `refresh()`;
  `MedicationsViewModel` is now a reactive `stateIn` stream (no `Loading` reset on
  entry). Tests updated to the reactive model.

## Issues encountered
1. **Wrong working directory (process).** Early edits targeted the main checkout
   `~/Development/tesseta` instead of the worktree; the sandbox correctly blocked
   them. Re-did everything against the worktree. No code impact.
2. **mockk + default args.** To avoid the default-argument mocking foot-gun, the new
   `MedicationRepository.observe()` takes no parameters (the list screen always wants
   all rows and partitions in the ViewModel); `list(status)` is retained for
   `ReminderSettingsViewModel`.
3. **First-sync flag vs. upgrade.** Users upgrading from a build before the flag
   existed have data but no `first_sync_complete`. Handled: when the flag is unset we
   fall back to the gate's `needsFirstSync()` (DB probe) once; if data already exists
   we release without the setup screen and latch the flag, so it's a one-time cost.

## Build / test status
- Whole project COMPILES (`:app`, `:core-data`, `:feature-medical`, all modules).
- Updated `MedicationsViewModelTest` (reactive model) passes.
- `:core-data:testDebugUnitTest`: the ONLY failure is
  `WorkoutProgramMapperTest > deep program carries embedded summary and demo frames`
  — **pre-existing and unrelated** to this branch. Verified by stashing all
  offline-fix changes and re-running on the clean base: it still fails there
  (`WorkoutProgramMapperTest.kt:141`, image-URL assertion). Out of scope for this
  fix; flagged here for a separate look.

## Follow-ups done (post-review)
- **`WorkoutProgramMapperTest` (the pre-existing failure) — fixed (test bug, not
  production).** `DemoFrameDto` gained `key/label/caption/order` BEFORE `phase`
  (IMPL-19), so the test's positional `DemoFrameDto("START", "https://x/a.jpg")`
  bound to `key`/`label` and left `imageUrl` null. Production decodes from JSON by
  field name, so the mapper (`imageUrl ?: imageCandidates.firstOrNull()`) was always
  correct. Fixed the test to use named args.
- **Medication detail is now offline-first.** It previously only fell back to the
  mirror AFTER the network `get()` failed, so every open showed a full-screen
  spinner and waited on the network. Added `MedicationRepository.cachedDetail()`
  (mirror-only, empty history) and reworked `MedicationDetailViewModel.refresh()` to
  seed from it instantly, then revalidate via `get()` to graft on the pull-only
  history (D9). The only network-only part of the detail is the history; the
  medication itself is fully mirrored. No `Loading` reset on re-entry / after edits.

## Follow-up — nutrition & blood "still loading" on reload (post-merge report)
Reported: nutrition and blood sections don't snap in like everything else.

- **Nutrition — real persistent bug, fixed.** `NutritionTodayViewModel.load()` was
  imperative (`loading=true` → one-shot `repository.day()`, no cache seed), so the
  Today screen spun on every open; and `cachedDay()` returned null for an empty day,
  so the dashboard nutrition card never seeded either. Fixes: (a) `cachedDay()` now
  returns the mirror-assembled day even when empty (an empty day is real data, not a
  reason to spin); (b) `load()` seeds instantly from `cachedDay()` and only shows the
  spinner when nothing is cached (pre-first-sync), revalidating via `day()` and
  keeping the cached day on error. The dashboard nutrition card (which already calls
  `cachedToday()`) now seeds because `cachedDay` no longer returns null.
- **Blood — already on the reactive architecture; left as-is.** `BloodOverviewViewModel`
  and `DashboardBloodViewModel` already read the Room mirror reactively
  (`combine(observeReadings(), observeReports())`), so they snap as soon as the mirror
  has data. The dashboard `BloodPanel` shows "No blood markers yet" (not a spinner)
  while empty. Any first-launch-after-update delay is the background backfill of
  older blood readings (blood tests are infrequent, so little/none falls in the
  14-day initial-sync window) populating the mirror — it self-heals on the next sync
  and snaps on subsequent launches. No code change; changing the initial `Loading`
  would also break `BloodOverviewViewModelTest`'s loading-then-ready contract.

## Scope decision — reactive UI-state hygiene (#8)
The primary "open app → see data" surfaces are fixed (launch, dashboard,
medications). The other list/overview screens (blood, body-composition, workouts
hub, dashboard-blood) already use reactive `stateIn` off the Room mirror, so their
`Loading` is a single pre-first-emission frame — no change needed. The remaining
imperative-`Loading` screens are SECONDARY and network-bound by nature, so they were
intentionally left alone: `MedicationDetailViewModel` (detail w/ pull-only history;
already falls back to the mirror offline), `GoogleHealthViewModel` (OAuth/settings),
`ProfileViewModel` (settings form). Worth a follow-up only if their brief loaders
prove annoying.
