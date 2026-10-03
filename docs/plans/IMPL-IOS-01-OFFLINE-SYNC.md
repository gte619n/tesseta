# IMPL-IOS-01 — Offline Sync Layer (SQLDelight mirror + outbox + engine)

> Living strategy + decisions log · Branch `ios-parity` · Updated 2026-10-02.
> Companion to [`IMPL-IOS-01-STATUS.md`](IMPL-IOS-01-STATUS.md),
> [`ios-parity-gap-report.md`](ios-parity-gap-report.md). This doc is BOTH the
> implementation strategy and the brief sub-agents execute the remaining phases
> from. Agents MUST append to §"Decisions for review" when they make a judgement
> call.

## Goal

Give the iOS client the real offline-first data layer Android has (ADR-0012):
an on-device encrypted mirror DB, a durable outbox for local writes, and a delta
sync engine — so screens read-through a local cache, writes survive offline /
process death, and the workout session logger (drafts that survive a crash) can
finally be built faithfully. Replaces the interim online-first repositories.

## Locked decisions (owner interview, 2026-10-02)

1. **SQLDelight, not Room-KMP.** Only 3 generic tables are needed; SQLDelight is
   the battle-tested Kotlin/Native path. `android/` doesn't consume `shared/` yet
   (D19/D20), so we don't lose shared DAOs today.
2. **Encrypt from the start, via app-layer `PayloadCipher`, not SQLCipher-native.**
   SQLDelight on K/N has no turnkey SQLCipher driver. We AES-GCM-encrypt the
   PHI-bearing columns (`mirrorRow.payloadCipher`, `outboxOp.docCipher`) before
   they touch SQLite; non-PHI metadata (collection/id/timestamps/status) stays
   plaintext so LWW + cursoring stay queryable. Key lives in the iOS Keychain.
3. **One generic `mirrorRow` table** keyed by (collection, id) — NOT Android's 25
   per-collection entities. The shared `MirrorStore` is generic; collection
   strings resolve via `CollectionRegistry`.

## Architecture (as built, Phases A–C)

```
SwiftUI view ─ collectFlow ─▶ shared ViewModel ─▶ Repository
                                                     │ reads: observe mirror (decrypt)
                                                     │ writes: mirror upsert (dirty) + outbox enqueue
                                                     ▼
   SyncEngineImpl ──pull──▶ SyncApi (GET /api/me/sync) ──▶ MirrorStore.applyServerChange (LWW)
        │  └──drain──▶ SyncApi.push ──▶ OutboxEndpointRegistry (real controller path)
        ▼
   MirrorDatabase (SQLDelight): mirrorRow · outboxOp · syncState   [PayloadCipher on PHI columns]
```

Key files (all under `shared/core/src/`):
- `commonMain/sqldelight/.../db/{MirrorRow,OutboxOp,SyncState}.sq` — schema + queries.
- `commonMain/.../sync/OutboxAndEngine.kt` — `MirrorStore`/`OutboxStore`/`SyncEngine`/`SyncApi` interfaces, `OutboxOp`, `CursorState`, `PushResult`, `MIRROR_SCHEMA_VERSION`.
- `commonMain/.../sync/SyncProtocol.kt` — `SyncResponse`/`ChangeDto`/`MergeConflictResolver` (LWW).
- `commonMain/.../sync/CollectionRegistry.kt` — collection→table + `MirrorTables`.
- `commonMain/.../sync/PayloadCipher.kt` — encryption seam (+ `NoopPayloadCipher` for tests).
- `commonMain/.../sync/MirrorDatabaseFactory.kt` (expect) + `appleMain`/`jvmMain` actuals.
- `commonMain/.../sync/SqlDelightStores.kt` — `SqlDelightMirrorStore` + `SqlDelightOutboxStore`.
- `commonMain/.../sync/OutboxEndpointRegistry.kt` — replay routing (relative paths).
- `commonMain/.../sync/KtorSyncApi.kt` — `SyncApi` over the shared Ktor client.
- `commonMain/.../sync/SyncEngineImpl.kt` — pull loop + outbox drain.

## Status

| Phase | What | State |
|---|---|---|
| A | SQLDelight toolchain + schema + cipher seam | ✅ `d198f724` |
| B | `MirrorStore` + `OutboxStore` (SQLDelight) + driver factory | ✅ `2458d7ec` |
| C | `SyncApi` + `SyncEngineImpl` + `OutboxEndpointRegistry` | ✅ `6be044b3` |
| D | Mirror-backed repositories (read-through / write-through) | ⬜ |
| E | iOS `SyncBridge` + DI + sign-out wipe + first-sync gate | ⬜ |
| F | `PayloadCipher` iOS actual (CryptoKit + Keychain) | ⬜ |
| G | Workout session repo on the mirror/outbox (drafts survive death) | ⬜ |

## Build / verify (from repo root)

```sh
# shared logic + sync tests (fast, JDK only):
cd shared && JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew :core:jvmTest --console=plain
# XCFramework (needs full Xcode; ~2.5min):
JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew :core:assembleSharedCoreXCFramework --console=plain
# app (from ios/):
cd ../ios && xcodegen generate && xcodebuild -project HealthFitness.xcodeproj -scheme HealthFitness \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
```
Kotlin 2.2.0, SQLDelight 2.1.0, Xcode 26.6. Gradle `cd` persists across the
sandbox's shell calls; prefer absolute paths.

## Gotchas (hard-won)

- **Nested block comments**: `/*` inside a KDoc opens a NESTED comment in Kotlin
  (e.g. `api/foods/*` swallowed a file). Avoid `*` in paths inside `/** */`.
- **`SharedCore.Food_`**: `data.Food` collides with another `Food`, so it exports
  with a trailing underscore. Check the generated header for exported names.
- **Bare type names in Swift resolve to the app-local struct** (they shadow
  `SharedCore`), so qualify the shared one as `SharedCore.X`. Nullable Kotlin
  `Double?` bridges as `KotlinDouble?` → use `?.doubleValue`. Kotlin `List<T>`
  bridges to Swift `[T]`.
- **libsqlite3**: once the native driver is actually instantiated on device, the
  app link may need `libsqlite3.tbd`. The app built clean through Phase C (driver
  not yet constructed from Swift); watch for undefined `sqlite3_*` at app link in
  Phase E and add the lib to `project.yml` if so.
- **expect/actual-classes-in-Beta** warning is benign (could silence with
  `-Xexpect-actual-classes`).
- `web/node_modules` is a symlink to the main checkout — irrelevant to iOS, ignore.

---

## Remaining phase briefs (for sub-agents)

Each agent: read this doc + the named Android reference, implement ONE phase,
verify green (jvmTest + XCFramework + app build where it touches iOS), commit with
a `feat(ios/sync): Phase X — …` message, append to §"Decisions for review", and
return a tight report. **If blocked or a design fork appears that isn't covered
here, STOP and report — do not hack around a red build or invent backend contracts.**

### Phase E-core first (DI foundation) — fold into the first agent

Before D can inject stores, `IosComposition` must own a single `MirrorDatabase` +
`SqlDelightMirrorStore` + `SqlDelightOutboxStore` + `SyncEngineImpl` (built from
`MirrorDatabaseFactory`, `KtorSyncApi(client, deviceId)`, and the injected
`PayloadCipher`). Add a persisted `deviceId` (UserDefaults UUID on iOS). Expose
accessors the repos use. This is the seam everything else hangs off.

### Phase F — `PayloadCipher` iOS actual

- Android ref: `DbKeystore.kt` (envelope: random data key, AES-GCM).
- iOS: a Swift `KeychainPayloadCipher` using CryptoKit `AES.GCM` with a 256-bit
  key stored in the Keychain (reuse the `KeychainTokenStore` patterns). Expose it
  to Kotlin by having `IosComposition.configure(...)` accept a `PayloadCipher`
  implemented in Swift (Kotlin `interface PayloadCipher` is implementable from
  Swift) — OR a Kotlin `actual` using platform.Security/CommonCrypto. Prefer the
  Swift-implements-the-interface route (CryptoKit is cleaner than CommonCrypto
  cinterop). JVM tests keep `NoopPayloadCipher`.
- Acceptance: encrypt/decrypt round-trips on device; wrong-key decrypt fails
  closed; the mirror file on disk shows ciphertext, not plaintext PHI.

### Phase D — mirror-backed repositories

- Android ref: `data/nutrition/NutritionRepository.kt` + `MirrorRepositorySupport.kt`
  (createLocal/updateLocal/deleteLocal → mirror upsert dirty + outbox enqueue;
  reads = `observeActive()` decode).
- Refactor the online-first repos (start with `HttpNutritionDayRepository`, then
  medications/goals/profile) to: READ by observing `mirrorRow` for the collection
  (decrypt payload → domain), WRITE by upserting the mirror (dirty) + enqueuing an
  outbox op, then letting the engine drain. Keep the same repository INTERFACES so
  the ViewModels/views are untouched. Preserve composite-id conventions
  (`"<date>/<entryId>"` etc.) the `OutboxEndpointRegistry` expects.
- A `MirrorRepositorySupport` helper in commonMain (createLocal/updateLocal/
  deleteLocal + observe/decode) will cut duplication — build it first.
- Acceptance: nutrition add/delete works offline (writes land in outbox, mirror
  updates optimistically) and converges after a drain; jvmTest coverage for the
  support helper + one repo.

### Phase E-rest — SyncBridge + sign-out wipe + gate

- Android ref: core-data sync bootstrap, `FirstSyncGate.kt`, `SignOutSideEffects.kt`.
- iOS `SyncBridge.swift`: on sign-in/foreground call `engine.pull()` then
  `engine.drainOutbox()`; publish `firstSyncComplete` to clear `FirstSyncGateView`;
  a periodic/`BGTask` refresh (D8) is a nice-to-have, wire at least foreground.
- **Sign-out wipe (B-6, PHI leak):** `AuthState.signOut()` must close + delete the
  mirror DB file (+ `-wal`/`-shm`), clear the outbox, drop the Keychain data key,
  and reset `firstSyncComplete`. Add a `MirrorDatabaseFactory.wipe()` /
  `IosComposition.wipeLocalData()`.
- Acceptance: cold launch shows the gate until first pull; sign-out leaves no
  mirror file; account-switch doesn't leak the prior user's data.

### Phase G — workout session repo on the mirror/outbox

- Android ref: `data/workouts/session/WorkoutSessionRepository.kt`.
- Implement `WorkoutSessionRepository` against the mirror (WORKOUT_SCHEDULED):
  `start` snapshots a draft row (dirty), set edits `updateSets` upsert the draft,
  `observeDraft` reads the mirror row, `finish`/`skip` enqueue the completion PUT
  (the `OutboxEndpointRegistry.workoutSessions` path), `discard` deletes locally.
  Drafts now survive process death (the original goal). Then wire
  `WorkoutSessionView` to the shared `WorkoutSessionViewModel` + factory.
- Acceptance: start → log sets → background/kill → relaunch resumes the draft;
  finish routes a completion through the outbox.

---

## Decisions for review

Running log of judgement calls made during implementation (newest last). Owner to
review.

- **(Phase A–C, main session)** Chose SQLDelight + app-layer `PayloadCipher` +
  single generic mirror table (see §"Locked decisions"). Single global sync cursor
  keyed `"__global__"` in `syncState` (backend delta is unified). Terminal 4xx in
  the drain self-heals by DROPPING the outbox op (can't wedge the queue); mirror-row
  self-heal deferred to the repos (Phase D). `recentSince` 14-day first-pull window
  NOT yet implemented — full backfill for now (heavier but correct); revisit if
  first sync is slow.
<!-- Sub-agents: append your decisions below this line. -->
- **(Follow-up #3 — DONE) Parked outbox state.** The Phase-C drain no longer DROPS a
  terminal 4xx — it PARKS the op (new `outboxOp.parked` column; held out of `listDue`,
  kept for recovery). `OutboxStore` gains `markParked/parked/observeParked/discard`;
  `SyncEngineImpl.drainOutbox` parks terminal failures (counts as `failed`).
  `MirrorWorkoutSessionRepository` now implements the parked-completion recovery for
  real: `observeParkedCompletions` maps parked WORKOUT_SCHEDULED ops → `ParkedCompletion`
  (programId/scheduledId from entityId; status/completedAt/loggedSetCount from the
  payload; sessionAvailable/dayLabel from the clean mirror snapshot; orphanedSetCount=0
  — full orphan detection vs the live plan is a follow-up), `restoreParked`
  re-materializes a draft from the rejected payload then drops the op, `discardParked`
  drops it. Tests + assemble + app build green.
- **(Phase F+DI)** `PayloadCipher.decrypt` is non-throwing in the Kotlin interface,
  so the iOS `KeychainPayloadCipher` **fails closed by returning an empty string**
  on an undecryptable token (wrong key / tampered / truncated). Empty is not valid
  JSON, so every downstream `Json.decodeFromString` throws rather than handing a
  caller plaintext garbage — PHI can't leak from a bad decrypt. Alternative
  (make the interface `@Throws`) was rejected to keep the hot read path simple;
  revisit if a caller needs to distinguish "absent" from "corrupt". Key is a 256-bit
  CryptoKit `SymmetricKey` in the Keychain (`kSecAttrAccessibleAfterFirstUnlock` so
  background sync can open the mirror while locked); `wipeKey()` exists for Phase E
  sign-out. `deviceId` is a UserDefaults UUID (resets on reinstall — a fresh install
  is a fresh mirror; a Keychain id could survive deletion and wrongly alias).
- **(Env note)** `:core:jvmTest` wedges in this worktree (daemon/test-worker lock
  contention) even with `--no-daemon`; verify shared changes with
  `:core:assembleSharedCoreXCFramework` + the app build. Agents: don't block on
  jvmTest — if it hangs >2min, kill + skip it for iOS-only phases.
- **(Phase E — reordered BEFORE D)** Wired `SyncBridge` to the real engine first so
  the engine actually runs (populating the mirror) before the repos read from it,
  and to land the sign-out PHI-leak fix early. `SyncBridge` calls `engine.pull()` +
  `drainOutbox()` (Kotlin suspend → Swift `async`, no SKIE) on first-gate + scenePhase
  `.active`; observes `firstSyncComplete()` Flow via `collectFlow` (KotlinBoolean). On
  any failure (incl. signed-out 401) it goes `.failed` but the gate still clears when
  a pull later succeeds. **Sign-out wipe (B-6):** `AuthState.signOut` →
  `IosComposition.wipeLocalData()` (mirror rows+cursor + outbox, non-suspend blocking
  SQLDelight clears) + `KeychainPayloadCipher().wipeKey()` (so residual ciphertext is
  unreadable + next user re-keyed); `SettingsView` also calls `sync.reset()` to flip
  the in-memory gate for same-session account switches. Swift gotchas fixed: `@Observable`
  stored-property init can't reference `Self.staticKey` (covariant-Self) → use a
  file-level `let`; a non-Sendable `any SyncEngine` can't be captured into a `Task` →
  resolve it inside the task. Foreground pull wired via `scenePhase`; FCM token
  registration + BGTask still TODO (not blocking).
- **(Phase D — scoped to the rail; full repo read-conversion deferred)** Built the
  reusable `MirrorRepositorySupport` (observe decrypted ACTIVE rows; createLocal/
  updateLocal/deleteLocal = optimistic dirty mirror row + outbox op + drain-kick,
  with a far-future `9999-local-…` provisional stamp so the local edit wins LWW) on
  new `SqlDelightMirrorStore` methods (observeActiveRecords/record/writeLocal/
  archiveLocal). jvmTest green. **Decision:** did NOT retrofit the already-working
  online-first repos (nutrition/meds/goals/profile) to mirror-read this pass —
  mirror-read needs client-side day/list ASSEMBLY from per-collection rows (Android's
  `assembleDay`) and carries real regression risk to working screens for mostly
  cold-start/offline-read polish. Instead the rail's FIRST consumer is Phase G
  (workout session), which is NEW (no regression surface) and is the headline goal
  (drafts survive process death). Retrofitting the CRUD repos onto the rail is tracked
  as follow-up polish. **Recommendation for review:** accept the rail + workout-session
  as the offline proof; schedule the nutrition/meds read-assembly conversion as a
  separate pass with device verification.
- **(Phase G — workout session data layer; view wiring deferred)** `MirrorWorkoutSessionRepository`
  stores the live draft in the `workoutScheduled` mirror row (id `"<programId>/<scheduledId>"`),
  REUSING the same id as the synced `ScheduledWorkout` and telling them apart by the
  `dirty` flag + payload shape (clean row = server `ScheduledWorkout` JSON; dirty row =
  `WorkoutSessionDraft` JSON). **Per-set edits are local-only** via the non-enqueueing
  `SqlDelightMirrorStore.writeLocal` (NOT the rail's createLocal/updateLocal, which
  enqueue per call) — only `finish`/`skip` enqueue the ONE idempotent completion op
  (routed to PUT …/sessions/{id}); `discard` clears the draft with no op. New store
  helpers: `deleteRowLocal` (hard clear after a terminal action) + `applyLocalSynced`
  (mirror a cold-miss calendar fetch as a CLEAN row so a later delta supersedes it).
  `start` sources prescriptions from the clean mirror row (or a best-effort calendar
  fetch) and returns `Result.failure` if offline + never synced — never invents plan
  data. **Known limitation (recorded):** parked-completion recovery
  (observeParkedCompletions/restoreParked/discardParked/reset) returns empty/no-op —
  the shared `OutboxStore` has no "parked" surface yet (Phase C drains DROP terminal
  4xx rather than parking). Add a parked/terminal-retained outbox state when that
  recovery UX is needed. **Follow-up:** wire `WorkoutSessionView` to the shared
  `WorkoutSessionViewModel` via the new `IosComposition.workoutSessionViewModel(programId:scheduledId:)`
  factory (the only remaining step for drafts-survive-death end-to-end).
- **(Phase G follow-up #1 — DONE) WorkoutSessionView wired to the shared VM.** The
  logger now observes `vm.state` + `vm.restTimer` via `collectFlow` and maps to its
  local mirror; all intents route to the VM (markStarted, toggleSet for log/undo via a
  rebuilt `PrescriptionKey(blockId,orderIndex)`, requestFinish/Skip/Discard +
  confirm*, dismissRest, pause/resumeTimer, confirmFinish(feeling), dismissCompleted →
  `closed` → pop). Added a shared `WorkoutSessionDraft.sessionRows(): List<SessionRow>`
  (primitives-only display rows + target-summary formatting) so the view never bridges
  the Kotlin `Map`/`Prescription` — keeps formatting single-sourced + testable.
  Reachable via the pre-existing `WorkoutsRoute.session` (WorkoutsHubView destination).
  Gotchas: Kotlin extension funcs on `WorkoutSessionDraft` export as INSTANCE methods
  (`draft.sessionRows()`), not statics on `SessionFormatKt`; Kotlin `Int?` param =
  Swift `KotlinInt?` (`confirmFinish(feeling: KotlinInt(int:))`); Kotlin enums compare
  with `==` (RestKind.getReady / SessionPrompt.finishSummary). **drafts-survive-death
  is now end-to-end in the UI.** Verified: assemble + app BUILD SUCCEEDED. (No unit test
  added for sessionRows — jvmTest wedges here; logic is simple + compile-verified.)
- **(Follow-up #2 — PARTIAL: clean CRUD repos retrofitted, intricate assemblies deferred)**
  `MirrorMedicationRepository` (observe MEDICATIONS mirror rows -> decode Medication;
  onStart + refresh = engine.pull) and `MirrorProfileRepository` (cached/get serve the
  USER_PROFILE mirror row first, network fallback; writes stay online PATCH) now back the
  meds list + profile screens: offline-read + instant cold start. Verified the sync-doc
  shapes decode 1:1 into the domain (medications.json fixture). Wired in IosComposition
  (Http* repos retired there). Deferred (regression risk, needs device verification):
  Nutrition-day + Goals (list+deep) mirror-read need client-side day/list ASSEMBLY from
  per-collection rows (Android's assembleDay / deep-goal join); those screens stay
  online-first (they work today). Follow the MirrorMedication pattern + a shared
  assembleDay/assembleGoalDeep helper, then device-verify.
- **(Follow-up #4 — DONE for what's not deployment-gated) BGTask + push.** New
  `AppDelegate` (@UIApplicationDelegateAdaptor) registers the two Info.plist BGTask
  ids — `…refresh` (BGAppRefreshTask → engine.pull) + `…processing` (BGProcessingTask
  → drainOutbox) — handlers resolve the engine inside the Task (Swift-6 Sendable) and
  chain the next request; `HealthFitnessApp` schedules them on scenePhase .background.
  `IosComposition.registerPushToken` does the real `PUT /api/me/devices/fcm {token,
  deviceId}`. Firebase/FCM is wired but GUARDED on a bundled `GoogleService-Info.plist`
  (absent in this repo): when present → FirebaseApp.configure + Messaging delegate →
  registerPushToken + APNs auth request; silent `content-available` push → pull+drain.
  **Deployment-gated remainder (NOT code):** ship `GoogleService-Info.plist`, add an
  APNs auth key to Firebase, and test on a real device — until then push is dormant
  while BGTask + foreground pull run. Gotcha: `MessagingDelegate` callback must be
  `nonisolated` (the class is @MainActor via UIApplicationDelegate). Verified: assemble
  + app BUILD SUCCEEDED (first Firebase SPM compile).
- **(Remaining follow-ups closed, 2026-10-03)** Merged origin/main (#292, backend-only
  drink-alcohol-calorie fix — nothing for the shared/iOS layer). Then:
  - **Nutrition + Goals mirror-read (#2 completion).** New `assembleNutritionDay` /
    `assembleGoalDeep` shared helpers + `SqlDelightMirrorStore.activeRecords` (snapshot).
    `MirrorNutritionDayRepository` (interface-delegates to Http; overrides `cachedDay`
    = assemble from the mirror, and `day` = network + seed the mirror keyed by plain
    entryId to match the sync engine) — a safe, no-regression offline read-through cache
    (observeDay/mutations stay on the working network impl). `MirrorGoalsRepository`
    (delegates to Http; `observeGoals` + `observeGoalDeep` read/assemble from GOALS/
    GOAL_PHASES/GOAL_STEPS mirror rows, onStart pull; mutations delegate). Decode shapes
    verified against the nutritionEntries/goals fixtures. NOTE: compile- + fixture-
    verified, NOT device-verified — a signed-in device pass is still owed.
  - **Orphaned-set detection (#3 completion).** `validPrescriptionKeys(scheduled)` now
    drives `ParkedCompletion.orphanedSetCount` (logged sets whose key left the current
    plan) and `restoreParked` drops orphans rather than restoring stale keys.
  - **Push activation (#4 completion).** The code is fully wired + guarded; the only
    remainder is owner provisioning — documented step-by-step in
    `ios/Config/PUSH-SETUP.md` (add the iOS app to Firebase project `health-fitness-160`
    for bundle `com.gte619n.healthfitness`, drop `GoogleService-Info.plist` into
    `ios/HealthFitness/`, upload an APNs auth key, verify on device). These are real
    secrets/accounts, not code.
  All: assembleSharedCoreXCFramework + iOS app BUILD SUCCEEDED; commonMain compiles.
- **(Blood + Body-Composition screens wired, 2026-10-03)** Bound `BloodOverviewView`
  and `BodyCompositionView` to their shared VMs (same SKIE-free `collectFlow` + map-to-
  local-mirror pattern as `NutritionTodayView`). New online-first repos in commonMain
  over the EXISTING backend (matched to Android's `BloodApi`/`DexaScanApi`/
  `BodyCompositionApi`): `HttpBloodReadingRepository` (GET/POST/DELETE api/me/blood),
  `HttpBloodTestReportRepository` (…/reports + per-report GET + PDF bytes),
  `HttpBodyCompositionRepository` (GET api/me/body-composition — snapshot DERIVED in the
  repo, a faithful port of Android's `buildSnapshot` incl. the 7d/90d deltas + 90-day
  weight/body-fat series; lenient nullable DTO drops incomplete rows like
  `toDomainOrNull`), and `HttpDexaScanRepository` (list/detail/field-PATCH/delete/PDF).
  All MutableStateFlow+onStart like `HttpMedicationRepository`; online-first was chosen
  over mirror-read because the overview snapshot needs client-side ASSEMBLY from
  per-metric rows (same deferral rationale as the nutrition/goals read-assembly pass).
  New `IosComposition.bloodOverviewViewModel()` / `bodyCompositionViewModel()` factories
  (the latter reuses the on-device `unitPrefs` for weight-unit projection).
  **Platform-stubbed (recorded, NOT wired):** the lab-PDF and DEXA-PDF uploads are
  online-only multipart-SSE AI flows (D17) with NO shared KMP client (Android's
  `MultipartSseClient` has no port) — both `upload`/`uploadPdf` emit a single
  graceful `Failed` so the upload sheets degrade; the document-picker + SSE stream is
  a separate platform task. Marker/report/scan DETAIL screens + Add-reading were left
  on their existing local-mock state (out of scope; they still compile).
  **Swift gotchas hit:** nested Kotlin enums export with dotted swift_names —
  `SharedCore.ExtractedMarker.Flag` (`.h`/`.l`) and `SharedCore.LatestMarker.Source`
  (`.manual`/`.lab`/`.none`), NOT top-level; the body-comp UiState is the nested
  `BodyCompositionViewModel.UiState`, while the Blood sealed UiState's cases are the
  top-level `BloodOverviewViewModelUiState{Ready,Error,Loading}`. `collectFlow` hands
  the closure an `Any` (cast with `as?` first). Kotlin `LocalDate`→`Date` via
  `toEpochDays()*86400`; `Instant`→`Date` via `toEpochMilliseconds()/1000`. Kotlin
  enums compare with `==`. `readBytes()` is deprecated → `readRawBytes()`.
  **For the next area (Workouts browse):** reuse this exact shape — add an online-first
  `Http*Repository` (MutableStateFlow+onStart) matching the Android API paths, an
  `IosComposition` factory, then bind the view via `collectFlow` + a static `map(...)`
  to the pre-existing local mirror structs; qualify shared types `SharedCore.X` and
  check the generated header for nested/underscored exported names before writing Swift.
  Verified: compileKotlinJvm + assembleSharedCoreXCFramework + iOS app BUILD SUCCEEDED.
- **(Workouts BROWSE screens wired, 2026-10-03)** Bound `WorkoutsLandingView` (hub),
  `ProgramsListView`, `ProgramDetailView`, `WorkoutDetailView`, `WorkoutHistoryView` to
  their shared VMs (same SKIE-free `collectFlow` + static `map(...)` → local-mirror
  pattern). New online-first repos in commonMain over the EXISTING backend (matched to
  Android's `WorkoutProgramApi` / `WorkoutSettingsApi`): `HttpWorkoutProgramRepository`
  (the full ~15-method `WorkoutProgramRepository` — observePrograms/observeProgram(deep)/
  observeCalendar/observeAllCompleted/completedWorkoutDays(workout-stats heatmap)/activate/
  continue/updateDetails/nutrition-guidance+target/runDayToday/workoutHistoryPage+cache/
  lastSetsFor) and `HttpWorkoutStreakSettingsRepository` (weeklyStreakTarget off
  `GET api/me/workout-programs/settings`). All `MutableStateFlow + onStart` like
  `HttpMedicationRepository`. The domain types are already `@Serializable` and wire-
  compatible 1:1 (verified against the backend: `trainingDays`/`dayOfWeek` + status/source
  enums all serialize UPPERCASE = the KMP enum names, so a direct `.body<WorkoutProgram>()`
  decode is safe — NO DTO shim), so only request bodies are private shims. New
  `IosComposition.workouts{Hub,ProgramsList,ProgramDetail,WorkoutDetail,WorkoutHistory}…`
  factories sharing ONE `HttpWorkoutProgramRepository` (warm shallow+deep caches across
  screens) and rebuilding `MirrorWorkoutSessionRepository` per factory exactly as the
  existing `workoutSessionViewModel` does (mirror+outbox+engine+client) — the browse
  screens only READ drafts/parked completions from it. Wired intents: activate, saveEdit
  (sheet), applyNutrition, startToday→push `.session`, resume-banner→push `.session`,
  history loadMore/pull-to-refresh. **Design calls:**
  - `observeCalendar`/`observeAllCompleted` are modelled as one-shot fetch flows
    (MutableStateFlow+onStart emitting a single fetch). `observeAllCompleted` has NO
    dedicated endpoint (Android reads its local mirror) — ported as a best-effort
    newest-first workout-history scan over the window (stops once a page predates
    `from`). Correct for the streak maths; heavier than a mirror read. Mirror-read
    assembly for the whole workouts vertical is deferred (same rationale as the
    nutrition/goals pass): online-first is right, just not cold-start/offline polished.
  - Programmatic nav (startToday/resume → logger) uses a local `navigationDestination(item:)`
    because the tab's `NavigationStack` is path-LESS (`NavigationLink(value:)` +
    `.navigationDestination`); there's no shared bound `NavigationPath` to append to.
  - `completedThisWeek`/`weekStreak`/the compliance grid are the shared `WorkoutsHubViewModel`/
    `ComplianceMath`'s — the view never recomputes. `WorkoutFormat.swift` gained Swift
    mirrors (statusLabel/trainingDaysSummary/dateLabel) alongside the existing set-format
    helpers; shared `ProgramFormat.kt` stays the SSOT (parity pinned by `WorkoutFormatTests`).
  **Skipped (out of scope — their backing repos aren't built yet, owned by the
  DESIGNER/PROGRESSION + GYMS agent):** `ProgressionConsoleView` (`ProgressionConsoleViewModel`
  needs `ProgressionRepository` + `WorkoutGoalsRepository` — INTERFACE-only in
  `WorkoutDesignerGymRepositories.kt`, no Http impl), `WorkoutLibraryView`
  (`AdHocLibraryRepository` — interface-only), and the Designer/Gyms views. These still
  compile on their local-mock state. The lab-PDF-style SSE Designer chat is a separate
  non-wire task. **Gotchas hit:** the `*/`-in-KDoc trap bit again (`api/me/*` inside a
  block comment silently closed it — "Unclosed comment" 230 lines later); a
  `response.bodyAsText()` in a non-suspend helper (`parseActivationIssues`) needed
  `suspend`; nullable Kotlin `Int?`→`KotlinInt?` bridges with `.intValue` (NOT
  `int32Value` — matches `ProfileView`), and the optional-chain needs parens before
  `.map` (`(x?.intValue).map{…}`); `WorkoutHistoryViewModel.State` exports DOTTED
  (`swift_name("WorkoutHistoryViewModel.State")`) while the top-level UiStates export flat
  (`WorkoutsHubUiState` etc.); `WorkoutProgram.description` collides → `description_`;
  `phaseProgress` is a `KotlinPair<KotlinInt,KotlinInt>` (`.first`/`.second` → cast
  `as? KotlinInt`); Kotlin enums compare with `==` (`ScheduledStatus.completed`,
  `ProgressionDirection.up`). Verified: compileKotlinJvm + assembleSharedCoreXCFramework
  + iOS app BUILD SUCCEEDED.
- **(Today DASHBOARD wired, 2026-10-03)** Bound `TodayView` (+ the shared-card
  `TodaySplitView`) to the shared `DashboardViewModel` (same SKIE-free `collectFlow`
  + static `map(...)` → local-mirror pattern). New `HttpDashboardRepositories.kt`
  implements ALL SEVEN `Dashboard*Repository` interfaces as thin online-first Http
  impls over the EXISTING endpoints Android's `data/dashboard/DashboardData.kt` uses
  (`GET api/me/body-composition`, `…/daily-metrics?from&to`, `…/blood`,
  `…/recent-activity?limit`, `GET api/me` for profile; nutrition reuses the shared
  `NutritionDayRepository.day`; today-workout reuses the warm
  `HttpWorkoutProgramRepository` calendar + the mirror-backed session drafts). The
  DERIVATION mappers (weight-summary 7/90-day deltas + downsample; blood-marker
  tone/fill/history; MET calorie recap) are VERBATIM ports of Android's
  `BodyCompositionMapper`/`BloodMarkerSummaryMapper`/`TodayWorkoutViewModel` (java.time
  → kotlinx-datetime), so the three clients render identical cards. New
  `IosComposition.dashboardViewModel()` wires all seven (sharing the body-comp repo
  so the completed-session recap reads the same latest bodyweight). **CardState
  mapping:** `DashboardUiState` is NOT a whole-screen sealed state — each card is its
  own `CardState<T>`, exported FLAT (not nested): `CardStateLoaded<AnyObject>` (read
  `.data` as `Any?`, cast to the element type), `CardStateError` (`.message`),
  `CardStateLoading`. `map(...)` folds every card's `.Loaded.data` into the local
  `DashboardModel`; a Loading/Error card maps to the dashlet's nil/empty placeholder
  (never a fabricated number), so one failed card never blanks the dashboard and the
  screen is always `.ready` after the first emission. Rendered the previously-orphaned
  blood + recent-activity dashlets (new `BloodDashlet`/`RecentActivityDashlet` +
  `BloodMarkerModel`/`RecentActivityModel` mirrors), gated on non-empty. **Cards left
  degraded (recorded):**
  - **Doses** — the shared `DashboardViewModel` exposes NO doses source (its UiState
    has no doses card; Android composes today's-doses in a separate feature VM), so the
    `DoseDashlet` is fed `[]` (reads "You're all caught up."). Wiring doses needs a
    dashboard doses repo + a VM field, or composing the existing `TodaysDosesViewModel`
    into the Today screen — a follow-up, not this pass.
  - **`cached*` = null/empty everywhere** — no on-device mirror read yet (same deferral
    as the nutrition/goals/blood/workouts online-first passes). The VM's
    no-Loading-reset invariant keeps the last Loaded value on a failed revalidate, so
    online-first still degrades gracefully; it's just not cold-start offline-polished.
    Recent-activity also has no persisted single-slot cache (Android uses a DataStore).
  **Swift gotchas hit:** `CardStateLoaded` is GENERIC → the cast needs an explicit
  arg (`as? CardStateLoaded<AnyObject>`), bare `as? CardStateLoaded` fails
  "generic parameter 'T' could not be inferred"; the nutrition totals/target map was
  too deep for the type-checker ("unable to type-check in reasonable time") → hoist
  `.data` casts into `let` sub-expressions first. `MarkerTone.good` compares with `==`
  (enum → class property). `Int32`→`Int()` for `setsLogged`/`totalSets`; `KotlinInt?`
  →`.intValue`, `KotlinDouble?`→`.doubleValue`; `Instant`→`Date` via
  `toEpochMilliseconds()/1000`; the Instant type bridges as `SharedCore.Kotlinx_datetimeInstant`.
  `TodayWorkout` sealed cases export FLAT (`TodayWorkoutResume/Start/Completed/Hidden`),
  switched via `case let x as SharedCore.TodayWorkoutResume`. Kotlin
  `WorkoutProgramRepository` exposes only Flow reads (no suspend list/calendar) → the
  dashboard workout repo resolves via `observePrograms().first()` /
  `observeCalendar(...).first()`. Verified: compileKotlinJvm + assembleSharedCoreXCFramework
  + iOS app BUILD SUCCEEDED.
- **(Goals ROADMAP wired, 2026-10-03)** Bound `GoalRoadmapView` (the deep-goal
  phases+steps timeline) to the shared `GoalRoadmapViewModel` via the same SKIE-free
  `collectFlow` + static `map(...)` → local-mirror pattern. New
  `IosComposition.goalRoadmapViewModel(goalId:)` constructs the VM over the SAME
  `MirrorGoalsRepository(HttpGoalsRepository, mirrorStore, syncEngine)` the list factory
  uses (REUSED the already-built repo — no new goals repo). Wired the read path (deep
  goal → ordered phases/steps) and all intents the VM supports: `toggleStep` (MANUAL
  step done-toggle; optimistic `pendingStepIds` disables the checkbox) and
  `resetStepToAuto`. **Recovered the two gap-report-flagged dropped affordances:** the
  per-step METRIC READOUT (`"<metricKey> <comparator.symbol> <target>[ for <windowDays>d]"`
  for bound steps, SUSTAINED-only window suffix) and the "Reset to auto" button (shown
  only on overridden non-MANUAL steps in a non-locked phase) — both are verbatim ports of
  Android `GoalRoadmapScreen.StepRow`. The `"Phase N of M · X of Y steps"` summary +
  date-range ("MAY 28 → JUL 12") formatting are ported from Android `GoalsFormat`
  (`GoalProgress.summary` / `formatDateRange`) into the Swift `map(...)` (Swift
  `DateFormatter`/`ISO8601DateFormatter`, date-only prefix(10) parse). The VM already
  intentionally OMITS the Android "Update nutrition"/`applyNutrition` action (cross-domain
  Workouts/Nutrition shared types not ported into Goals) — nothing to wire there.
  **Skipped (separate task): `GoalsChatView`** — it needs the SSE streaming transport
  (`GoalsChatViewModel(sseClient:chatRepository:idGenerator:json:)`), which is a
  build-not-wire task (no shared SSE client wired on iOS yet, same gap as the lab-PDF/
  Designer chat flows); left on its local-mock state, still compiles. **Swift gotchas
  hit:** `GoalRoadmapUiState` is FLAT (not nested) with `pendingStepIds` as
  `NSSet<NSString *>` → `Set(s.pendingStepIds.compactMap { $0 as? String })`;
  `GoalDeep`/`Phase`.`description` collide → `description_`; `Step.metricRegressed` is
  `KotlinBoolean?` → `.boolValue`; `StepMetricBinding.windowDays` is `KotlinInt?` →
  `.intValue` (`targetValue` is a non-null `double`); nested-type qualify
  `SharedCore.Step`/`SharedCore.Phase`/`SharedCore.PhaseStatus`/`SharedCore.StepKind`
  (bare `Step`/`PhaseStatus` shadow the app-local structs); enums compare with `==`
  (`status == SharedCore.PhaseStatus.completed`, `kind == SharedCore.StepKind.manual`,
  `kind == .sustained`); `collectFlow` hands the closure `Any` (cast `as? GoalRoadmapUiState`
  first). Changed `GoalRoadmapView`'s implicit memberwise init to an explicit `init(goalId:)`
  that builds the VM (same shape as `GoalsListView`) — no callers yet (`GoalsRoute.roadmap`
  has no live `.navigationDestination` consumer), signature unchanged. Verified:
  compileKotlinJvm (UP-TO-DATE — factory is in iosMain) + assembleSharedCoreXCFramework
  + iOS app BUILD SUCCEEDED.
- **(Secondary detail screens pass, 2026-10-03)** Swept the eight SECONDARY-screen
  candidates (Medications detail/doses/add/reminder-settings, Nutrition target +
  edit/adjust-review/leftover-review sheets, Settings sync-diagnostics/workout-prefs/
  drinks) against the "wire-only-if-the-shared-VM-exists-AND-its-repo-deps-are-built"
  rule. **Wired exactly ONE:**
  - **`NutritionTargetView`** → shared `NutritionTargetViewModel` over
    `HttpNutritionDayRepository` (`GET/PUT api/me/nutrition/target`; the repo's
    `target()`/`setTarget()` already exist). New `IosComposition.nutritionTargetViewModel()`;
    the view now binds via `collectFlow` + a static `map(...)` to a local `ScreenState`
    and re-seeds the editable `Draft` from the server target on each emission EXCEPT
    while a save is in flight (so in-progress edits aren't stomped). `save` hands a
    shared `Macros` straight to the VM. Online form only — no mirror/outbox, same as
    Android. Swift gotcha: `m?.kotlinDoubleProp?.doubleValue` reads as a non-optional
    `Double` in a `?.map { … }` chain ("value of type 'Double' has no member 'map'") →
    hoist the unwrap into a tiny `func str(_ k: KotlinDouble?) -> String` guard. The
    shared `Macros` ObjC init is the 6-arg compat ctor
    (`caloriesKcal:proteinGrams:carbsGrams:fatGrams:fiberGrams:sugarGrams:`, each
    `KotlinDouble?` — no alcohol arg exported); `NutritionTargetUiState`/`Macros`
    export FLAT (no underscore). Verified: compileKotlinJvm UP-TO-DATE (factory is
    iosMain) + assembleSharedCoreXCFramework + iOS app BUILD SUCCEEDED.
  - **SKIPPED — Medications `MedicationDetailView` / `TodaysDosesView` /
    `AddMedicationView` / `ReminderSettingsView`.** Their VMs exist
    (`MedicationDetailViewModel` / `TodaysDosesViewModel` / `AddMedicationViewModel` /
    `ReminderSettingsViewModel`) but depend on `MedicationCrudRepository`,
    `AdherenceRepository`, `DrugRepository`, `ReminderSettingsRepository` — ALL still
    INTERFACE-ONLY (declared in `MedicationRepositories.kt`; its own KDoc says "The
    CONCRETE implementations … are the remaining Phase 1C body"). The wired meds LIST
    uses `MirrorMedicationRepository`, which implements the read-only
    `MedicationRepository`, NOT the CRUD surface these need. `AddMedicationView` also
    needs the SSE drug-lookup transport. Build-not-wire; left on local-mock, compiles.
  - **SKIPPED — Settings `DrinkSettingsView`.** `DrinkSettingsViewModel` exists but
    needs a concrete `DrinkRepository` (interface-only in `SettingsRepositories.kt`),
    plus the AI analyze + image-regen flows. Build-not-wire; left on local-mock.
  - **SKIPPED — Settings `WorkoutPreferencesEditor`.** `WorkoutPreferencesViewModel`
    exists but needs a concrete `WorkoutSettingsRepository` (interface-only — the only
    references are the VM itself). Build-not-wire; left on local-mock.
  - **SKIPPED — Settings `SyncDiagnosticsView`.** There is NO shared sync-status VM
    (grep of `presentation/` finds none); its parity target `SyncStatusViewModel` was
    never ported. Surfacing the engine/outbox state is a `SyncBridge`-style platform
    task (derive a `SyncUiState` off `SyncEngine`/`OutboxStore`), not a "wire to an
    existing VM" job. Left on local-mock.
  - **SKIPPED — Nutrition `MealAdjustReviewView` / `LeftoverReviewView` (+ the
    EditEntry sheet).** `MealAdjustViewModel` / `LeftoverViewModel` exist and depend
    only on the built `NutritionDayRepository`, BUT they are CONSTRUCTED with the
    `MealAdjustment` / `Leftover` PROPOSAL passed in — and that proposal is NOT
    retrievable from the current repo/VM surface: there is no `adjustmentFor(entryId)`
    repo method, the shared `Entry` domain carries no `adjustment`/`leftover` field,
    and `NutritionTodayUiState` exposes only the open/close `reviewingAdjustId`/
    `reviewingLeftoverId` (not the diff). On Android the proposal arrives via the FCM
    adjust-review / leftover-review notification PAYLOAD (the deep-link path), which is
    not wired on iOS yet (same transport gap as the capture op-rail / GoalsChat SSE).
    Wiring the sheets today would only reproduce the current nil-proposal placeholder,
    so they stay on local-mock. **Recommendation:** wire these together with the FCM
    deep-link handler that mints the `MealAdjustViewModel`/`LeftoverViewModel` from the
    notification payload (a notifications task), not the plain repo rail.
- **(Medications CRUD repos BUILT + detail/doses/add/reminders WIRED, 2026-10-03)**
  Closed the only remaining SKIP from the secondary-detail pass. **Repos built** (new
  `HttpMedicationRepositories.kt`, all online-first `MutableStateFlow + onStart` like
  `HttpMedicationRepository`, matched to the backend controllers 1:1):
  - `HttpMedicationCrudRepository` — list(`GET …/medications?status=`), today
    (`GET …/today?date=`, reactive StateFlow), create/update/dosage/discontinue/
    reactivate/delete. **Wire shapes verified against the backend:** the detail
    `GET …/{id}` returns a FLAT `MedicationDetailResponse` (all `Medication` fields +
    `history` inline) — the shared `MedicationDetail` is NESTED `{medication, history}`,
    so I decode a private flat `MedicationDetailWire` and assemble. create/update/dosage/
    discontinue/reactivate all return a `WriteResult<T>` = `{data, lastUpdate}` envelope,
    so `create()` unwraps `.data` (the other mutations ignore the body). `cachedDetail`
    throws (no mirror on online-first) — the VM's `runCatching` swallows it and falls back
    to the network `get()`.
  - `HttpAdherenceRepository` — logDose(`POST …/{id}/adherence`), undoDose
    (`DELETE …/{id}/adherence/{date}/{window}`), markMissed (POST with `missed:true`). It
    SHARES the crud repo instance and kicks `refreshTodaysDoses()` after each write so the
    single reactive today's-doses StateFlow re-emits (online-first stand-in for Android's
    mirror re-emit — no manual optimistic flip in the view, parity preserved).
    `takenWindowsFor`/`recordedWindowsFor` are best-effort (derive today's taken windows
    from the cached projection; the backend has no cross-med adherence-by-date endpoint —
    Android reads these from the mirror) and are on NO wired view's path.
  - `HttpReminderSettingsRepository` — `GET`/`PUT …/reminder-settings`; decodes 1:1 into
    the shared `ReminderSettings` (enum-name map keys match). `getCached` serves the last
    in-memory read then defaults (no persisted cache online-first).
  - `HttpDrugRepository` — catalog `GET /api/drugs` (live). **DEGRADED:** `lookupStream`
    emits a single `DrugLookupEvent.NotFound` — the AI SSE lookup
    (`POST /api/drugs/lookup/stream`) has no shared KMP SSE client yet (same gap as the
    lab-PDF / Designer / GoalsChat flows). The Add screen stays usable via catalog match +
    manual entry; TODO noted in the repo.
  **Factories** (IosComposition): `medicationDetailViewModel(medicationId:)`,
  `todaysDosesViewModel()` (crud shared between the crud+adherence repos),
  `addMedicationViewModel()` (`isOnline` pinned `MutableStateFlow(true)` — no connectivity
  source yet), `reminderSettingsViewModel()`. `onReplan` is a no-op everywhere (no shared
  reminder scheduler; the platform replans its local notifications separately).
  **Views wired** (all `collectFlow` + static `map(...)` → local mirror, SKIE-free):
  `MedicationDetailView` (observes state + reminder + `deleted`→dismiss; discontinue uses
  the default OTHER reason + today end-date — a detailed reason sheet is a follow-up;
  reminder toggle → onReminderChange+saveReminder), `TodaysDosesView` (toggle → `vm.toggle`),
  `AddMedicationView` (onQueryChange/selectDrug/startManualEntry/backToSearch/submit; builds
  `CreateMedicationRequest` + `InlineReminderConfig`, drug search degrades gracefully),
  `ReminderSettingsView` (master switch + per-window `DatePicker`↔"HH:mm" + per-med mutes →
  setEnabled/setWindowTime/setMedEnabled, save). **Degraded/skipped:** only the AI drug SSE
  lookup (above). **Swift gotchas hit:** the `*/`-in-KDoc trap bit again inside a backticked
  path (`.../*Api.kt`) → "Unclosed comment" — rephrase without `*`. `MedicationDetailUiState`/
  `TodaysDosesUiState` sealed cases export FLAT (`…UiStateReady/Error/Loading`); the
  `AddMedicationUiState`/`ReminderSettingsUiState` are data-class structs (flat props);
  `AddMedicationUiState.Step` is the NESTED `SharedCore.AddMedicationUiState.Step`
  (`.search/.form/.custom`), enums compared with `==`. `Boolean` flow value →
  `as? KotlinBoolean` then `.boolValue` (the `deleted` flow + `vm.online.value`). `Int?` ctor
  args → `KotlinInt?` (FrequencyConfig timesPerPeriod passed nil); `LocalDate` built via
  `Kotlinx_datetimeLocalDate(year:monthNumber:dayOfMonth:)`; `Instant`→`Date` via
  `toEpochMilliseconds()/1000` (type `SharedCore.Kotlinx_datetimeInstant`); `TimeWindow`/
  `DiscontinueReason`/`FrequencyType` qualify `SharedCore.X` and compare with `==`. The four
  `MedicationsRoute` destinations still have no live `.navigationDestination` consumer (the
  views compile + work when reached; `MedicationDetailView` now takes `init(medicationId:)`
  matching `.detail(String)`). **For the next area:** the SSE transport (drug lookup, GoalsChat,
  Designer chat, lab/DEXA PDF) is now the dominant un-wired gap across the app — building ONE
  shared KMP SSE client would unblock all of them at once. Verified: compileKotlinJvm +
  assembleSharedCoreXCFramework + iOS app BUILD SUCCEEDED.
- **(Screen-wiring batch: workout settings/progression/adhoc)** Built
  `HttpWorkoutSettingsRepository`, `HttpProgressionRepository`, `HttpWorkoutGoalsRepository`,
  `HttpAdHocLibraryRepository` (online-first) + factories; wired `WorkoutPreferencesEditor`,
  `ProgressionConsoleView`, `WorkoutLibraryView`. (Sub-agent authored the code; I fixed 3
  flattened nested-type names it guessed — the exports are DOTTED:
  `WorkoutPreferencesViewModel.SaveState`, `ProgressionConsoleViewModel.State/.Companion` —
  always grep the generated header's swift_name before referencing a nested Kotlin type.)
- **(GYMS repos BUILT + list/detail/new/edit/scan WIRED, 2026-10-03)** Built the three
  previously interface-only gym contracts as online-first Http impls (new
  `HttpGymRepositories.kt`) over the EXISTING backend, matched 1:1 to Android's
  `LocationApi`/`EquipmentApi`/`GymScanApi` and verified against the
  `LocationController`/`EquipmentController`/`GymVideoScanController`:
  `HttpLocationRepository` (GET/POST/PATCH/DELETE `api/me/gyms` + `/default` + multipart
  `/photo`), `HttpEquipmentRepository` (`GET api/equipment/{id}`), `HttpGymScanRepository`
  (register → signed-URL PUT → start → poll → confirm). New factories in `IosComposition`
  (`gymsListViewModel`, `gymDetailViewModel`, `makeNewGymViewModel`, `editGymViewModel`,
  `gymScanViewModel`) sharing one `HttpLocationRepository`+`HttpEquipmentRepository`; wired
  all five gym Swift views via the SKIE-free `collectFlow` + static `map(...)` pattern. The
  `WorkoutsHubView` dispatcher arms for the gym routes were already in place.
  **Wire subtleties (verified against backend):**
  - **Gym-hours map keys serialize LOWERCASE** (`"mon"`…`"sun"`) via
    `DayOfWeekJacksonConfig`'s key (de)serializer — NOT the enum name. The shared
    `Location`/`Create`/`UpdateLocationRequest` type `hours` as `Map<DayOfWeek, HoursSlot>`,
    whose default kotlinx enum-key codec would emit UPPERCASE. Avoided the custom-KSerializer
    route (the `DayOfWeek.kt` Phase-1C TODO) by keeping private wire DTOs keyed by a plain
    `String` and mapping to/from `DayOfWeek` explicitly (`name.lowercase()` out,
    case-insensitive match in). This resolves the doc's standing hours-serializer TODO for
    the iOS read/write path.
  - `create`/`update` return `WriteResult<LocationResponse>` with `@JsonUnwrapped` body — the
    LocationResponse fields are FLAT plus a sibling `lastUpdate`; a lenient decode straight
    into the wire DTO drops `lastUpdate` (no envelope unwrap).
  - **No per-equipment DELETE endpoint exists** on the gym controller (Android's `LocationApi`
    declares one but the backend has only PATCH-specs + the scan-confirm add path). So
    `removeEquipment` reads the current `equipmentIds`, drops the one, and PATCHes the reduced
    list — matches the only server-supported detach path.
  - The cover photo is a multipart POST (field `file`) to `…/gyms/{id}/photo`; the scan video
    is a direct-to-GCS signed PUT via a SEPARATE bare `HttpClient()` (no Authorization / JSON
    content-type, mirroring Android's `SignedUploadClient`) so GCS accepts the raw bytes.
  **SKIPPED (separate author-the-VM task, as the brief allows):** the manual add-equipment
  and per-gym spec-OVERRIDE dialogs — there are NO shared `AddEquipmentViewModel` /
  `EquipmentOverrideViewModel` in `presentation/workouts/` (only `GymsList/GymDetail/NewGym/
  EditGym/GymScan` VMs exist), and the per-gym equipment-specs PATCH
  (`updateEquipmentSpecs`) is not on any shared repo interface. Those are a backend-VM-port
  task, not a wire-up. The gym form's per-day HOURS grid is also not surfaced in the shared
  `LocationFormState`-backed `GymFormView` (only the 24-hour toggle), so `hours` is sent empty
  (the shared `hoursForWire()` then omits it) — parity with the current form's capability; a
  per-day hours editor is a follow-up.
  **Swift gotchas hit:** a Kotlin factory whose ObjC selector STARTS WITH `new`
  (`newGymViewModel`) collides with the ObjC `new` method family and is **silently DROPPED
  from the generated header** (the build then fails "no member newGymViewModel") — renamed to
  `makeNewGymViewModel`. Also: an incremental `assembleSharedCoreXCFramework` can report
  UP-TO-DATE and leave a stale header; a real iosMain source change forces the re-export. The
  nested VM states export DOTTED (`GymsListViewModel.UiState`, `GymDetailViewModel.UiState`,
  `GymScanViewModel.{Stage,Row,UiState}`); `Amenity.companion.fromId(id:)`; `PendingUpload`
  needs a Swift `Data → KotlinByteArray` bridge (`KotlinByteArray(size:)` + `set(index:value:)`
  with `Int8(bitPattern:)`); `LocationFormState.hours` is a NON-optional `NSDictionary` (pass
  `[:]`); `Set<Amenity>` bridges to the `NSSet<Amenity>` the form state wants; the `rows` map is
  `NSDictionary<KotlinInt, Row>` (key with `KotlinInt(int:)`); `Stage`/enums compare with `==`;
  `collectFlow` hands the closure `Any` (cast first); `uploading` Flow value is `KotlinBoolean`
  (`.boolValue`). Verified: compileKotlinJvm + assembleSharedCoreXCFramework + iOS app
  BUILD SUCCEEDED.
- **(DrinkRepository BUILT + Settings › Drinks WIRED, 2026-10-03)** Closed the last
  secondary-detail SKIP. Built the previously interface-only `DrinkRepository` as a thin
  online-first `HttpDrinkRepository` (new `HttpDrinkRepository.kt`) over the EXISTING backend,
  matched 1:1 to Android's `DrinkApi`: `listMyDrinks` (`GET api/me/drinks`), `analyze`
  (`POST …/drinks/analyze`), `createDrink`/`updateDrink` (`POST`/`PUT …/drinks[/{id}]`),
  `regenerateImage` (`POST …/{id}/image/regenerate`), `reorder` (`PUT …/drinks/order`),
  `archiveDrink` (`DELETE …/{id}`). New `IosComposition.drinkSettingsViewModel()`; wired
  `DrinkSettingsView` + its editor sheet to the shared `DrinkSettingsViewModel` via the
  SKIE-free `collectFlow` + static `map(...)` pattern. The editor-sheet field edits route
  THROUGH the VM (`updateEditor { doCopy(...) }`) so the shared VM stays the single source of
  truth (raw strings, parsed on `save`); per-field `with*` copy helpers wrap the 14-arg
  `doCopy`. The sheet is driven off `UiState.editor != nil` (a Binding that calls
  `closeEditor()` on dismiss). **AI flows are all plain (non-SSE):** `analyze` is one JSON
  round-trip and image-regeneration is fire-and-poll (the VM already polls the list while any
  image is PENDING) — nothing degraded, no SSE needed. **Decisions:** the `analyze` 422
  "AI unavailable" path is split by catching `ClientRequestException` and testing
  `status == UnprocessableEntity` (under `expectSuccess = true` a non-2xx throws), mapping to
  `AnalyzeResult.Unavailable`; any other throwable → `AnalyzeResult.Error`. The request/response
  DTOs reuse the shared 6-field `Macros` (no `alcoholGrams` field — the mixer macros are
  carbs/sugar only, and the backend's extra `alcoholGrams` macro key on reads is dropped by the
  shared Json's `ignoreUnknownKeys`). This is the Drink SETTINGS/management surface only — the
  Drink-Mode SESSION feature has no shared VM and stays out of scope. **Swift gotchas hit:**
  the nested data-class states export DOTTED — `DrinkSettingsViewModel.UiState` /
  `DrinkSettingsViewModel.EditorState` (NOT the flattened `DrinkSettingsViewModelUiState` the
  sealed-case medications states used — grep the header's swift_name before guessing); TWO
  `Food` data classes in shared commonMain collide, so the Settings `presentation.settings.Food`
  exports as plain `Food` (qualify `SharedCore.Food`) while the catalog
  `data.NutritionRepositories.Food` loses the name and exports as `Food_` (likewise
  `ServingSize`→`ServingSize_`); `updateEditor` takes a Kotlin `(EditorState)->EditorState`
  block; `Double?` readouts are `KotlinDouble?` → `.doubleValue`; the VM intents bridge as
  `archive(drink:)`/`moveUp(drink:)`/`regenerateImage(drink:)`/`openEdit(drink:)` taking the
  shared `Food`. Verified: compileKotlinJvm + assembleSharedCoreXCFramework + iOS app
  BUILD SUCCEEDED.
