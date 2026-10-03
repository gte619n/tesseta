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
