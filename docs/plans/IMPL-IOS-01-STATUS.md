# IMPL-IOS-01 — Status & Next Steps

> Living snapshot · Updated **2026-10-04** · Branch **`ios-parity`** (29 commits
> ahead of `origin/main`; based on `origin/main` incl. #292). Companion to
> [`IMPL-IOS-01-ios-client-parity.md`](IMPL-IOS-01-ios-client-parity.md) (spec),
> [`IMPL-IOS-01-OFFLINE-SYNC.md`](IMPL-IOS-01-OFFLINE-SYNC.md) (**the live
> decisions log — per-phase + per-screen rationale**),
> [`ios-parity-matrix.md`](ios-parity-matrix.md),
> [`ios-parity-gap-report.md`](ios-parity-gap-report.md) (2026-09-23 audit; now
> largely superseded by this doc).

## TL;DR (2026-10-04)

The iOS client now has **the real offline-first data layer** and **33 screens bound
to the shared KMP ViewModels**. Everything below is **compile/assemble/app-build
green** (XCFramework for iosArm64 + iosSimulatorArm64, and the SwiftUI app for the
iPhone-17/iOS-26.5 simulator). **NOTHING in this branch has been run signed-in on a
device/simulator, and NONE of it is on TestFlight** — it is 29 unpushed/unmerged
commits on `ios-parity`.

- **Offline sync layer (A–G) — complete.** SQLDelight mirror + outbox + delta sync
  engine + app-layer AES-GCM `PayloadCipher` (Keychain key) + `SyncBridge`
  (foreground/BGTask pull, first-sync gate) + **sign-out wipe (B-6 PHI-leak fixed)** +
  parked-op recovery. Workout session is a device-local draft that survives process
  death (the ADR-0012 goal), routed to the backend via the idempotent outbox.
- **33 screens on shared VMs** (was 8): Today dashboard, Nutrition (today/add/target),
  Medications (list/detail/doses/add/reminders), Blood, Body-Composition, Workouts
  (hub/landing/programs/detail/history/progression/library/session), Gyms
  (list/detail/new/edit/scan), Goals (list/roadmap/chat), Workout Designer, Profile,
  Units, Coach-audio, Drink settings, Workout preferences.
- **All concrete repos built**: the networked `Http*Repository` layer over the
  existing backend (nutrition/food/medications-crud/adherence/drug/reminder-settings/
  blood/body-comp/dexa/workout-program/settings/progression/goals/adhoc/location/
  equipment/gym-scan/drink/chat) + the `KtorSseClient` streaming transport.

## Build / verify (from repo root)

```sh
cd shared && JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew --no-daemon \
  :core:compileKotlinJvm :core:assembleSharedCoreXCFramework --console=plain
cd ../ios && xcodegen generate && xcodebuild -project HealthFitness.xcodeproj \
  -scheme HealthFitness -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
```
Kotlin 2.2.0, SQLDelight 2.1.0, Xcode 26.6. **Do NOT run `:core:jvmTest`** — it wedges
in this worktree (daemon lock); verify via `compileKotlinJvm` + `assembleSharedCoreXCFramework`
+ the app build. `pkill -9 -f GradleDaemon` if a gradle build hangs.

## Architecture (as built)

```
SwiftUI view ─ collectFlow ─▶ shared ViewModel ─▶ Repository
   │  (init: IosComposition.shared.xViewModel();                 ├─ networked: Http*Repository (.body())
   │   onAppear collectFlow(vm.state){ map→@State mirror })      ├─ mirror-read: assemble from SQLDelight rows
   ▼                                                             └─ writes: mirror upsert(dirty) + outbox enqueue
IosComposition (iosMain DI) ── configure(baseUrl, tokenProvider, cipher, deviceId)
   builds once: MirrorDatabase + Sql​Delight{Mirror,Outbox}Store + KtorSyncApi + SyncEngineImpl
        │  pull: GET /api/me/sync → applyServerChange (LWW: MergeConflictResolver)
        └  drain: outbox → OutboxEndpointRegistry → real controller path (terminal 4xx → parked)
   MirrorDatabase (SQLDelight): mirrorRow · outboxOp · syncState  [PHI columns AES-GCM via PayloadCipher]
```
Locked decisions (owner interview): **SQLDelight (not Room-KMP)**; **app-layer
`PayloadCipher` (not SQLCipher-native)**; **one generic `mirrorRow` table**. Full
rationale + every judgement call: [`IMPL-IOS-01-OFFLINE-SYNC.md`](IMPL-IOS-01-OFFLINE-SYNC.md).

## What's DONE (this branch, 29 commits)

| Area | State | Commits |
|---|---|---|
| Offline sync A–G (toolchain→storage→engine→cipher/DI→SyncBridge+signout-wipe→rail→session) | ✅ build-green | d198f724 2458d7ec 6be044b3 39988a77 898fd515 8361f324 def39ec9 e1ed21df |
| Mirror-read repos (nutrition/goals/meds/profile) + parked recovery + BGTask/FCM-reg | ✅ build-green | 6d317304 492b055c 0643f88c af894ae0 63f6c33d 355fb7d3 |
| Meal logging (nutrition today/add/target) | ✅ build-green | 39d77532 49e97286 69dcac2a |
| Screen wiring: Blood/BodyComp, Workouts browse, Today, Goals roadmap | ✅ build-green | eb96b9f6 81ecb7c8 a9a785d6 de48fa61 |
| Concrete repos + wiring: meds CRUD, workout settings/progression/library, gyms, drink | ✅ build-green | 18285916 f422f4ed 0dd6d70b 662e117b |
| SSE (Goals Chat + Workout Designer) + Goals/Medications navigation | ✅ build-green | ce80d269 45ae0d20 |

## What's NOT done

1. **ON-DEVICE VERIFICATION — the #1 gap.** Nothing in this branch has run signed-in
   on a device/sim (can't be done headlessly here). All 33 screens + the sync engine
   are compile/build-verified only; runtime bridging issues (decode shapes, flows,
   actor hops) are unproven. **Do this before trusting breadth / before a real release.**
2. **TestFlight / deploy** — not pushed, not merged, not released. The
   `release-ios-on-main.yml` fastlane→TestFlight lane triggers only on push to `main`.
   A device build also needs real signing (match + ASC key, already in GCP Secret
   Manager) which the simulator builds skip (`CODE_SIGNING_ALLOWED=NO`).
3. **Push activation (FCM)** — code wired + guarded; needs a bundled
   `GoogleService-Info.plist` + an APNs auth key (deployment steps, see
   [`ios/Config/PUSH-SETUP.md`](../../ios/Config/PUSH-SETUP.md)). Dormant until then.
4. **Author-new-shared-VM features** (never ported to the shared layer — *build*, not
   *wire*): Drink Mode session (B-4), PlanCoherence overlay (B-9), Google Health
   connection (B-8), manual add-equipment + spec-override (B-10), Goals roadmap
   "Update nutrition" (B-11), Workout Designer proposal-edit tree + TRT safety panel (B-3).
5. **Platform services**: medication reminder scheduling (D9 — `plannedDoses()` still
   returns `[]`), TTS coach voice (`AVSpeechSynthesizer`).
6. **Smaller leftovers**: Blood marker-detail / DEXA-detail drill-downs; lab/DEXA
   document-picker + QuickLook PDF; nutrition adjust/leftover review (needs the FCM
   proposal payload + deep-link); Sync Diagnostics (needs a sync-status VM);
   fully-reactive-offline `observeDay` (currently cold-start read-through cache).
7. **android/ consumes shared** (D19/D20 soak gate) — untouched by design.

Correctly NOT VM-bound (not gaps): MoreView (static menu), ServingHintView /
GymFormView / WorkoutRecapView (sub-components), WorkoutsHubView / SettingsView
(route dispatchers), TodaySplitView (iPad layout variant of the wired TodayView).

## Recurring gotchas (hard-won — see the decisions log for the full list)

- Nested Kotlin types export **DOTTED** (`VM.State`, not `VMState`) — grep the
  generated `SharedCore.h` `swift_name(...)` BEFORE referencing one.
- A `new`-prefixed ObjC selector (`newXViewModel`) is **silently dropped** — use `makeNew…`.
- Bare Swift type names **shadow** `SharedCore` → qualify `SharedCore.X`. A colliding
  `data.X` exports as `X_` (two `Food`/`ServingSize` exist).
- `Double?`→`KotlinDouble?.doubleValue` (also `Int?`/`Boolean?`); `Int` param→`Int32`;
  Kotlin extension fn → **instance method** on the receiver.
- `@Observable` init can't reference `Self.staticMember`; a non-Sendable captured into
  `Task{}` must be resolved inside the task; `/*` in a KDoc path opens a nested comment.

## Next steps (recommended order)

1. **Device-verification pass** (sign in; exercise the wired screens + a sync
   round-trip; fix the runtime issues compile-green can't catch).
2. **Push `ios-parity` → PR → merge to `main`** to ship a TestFlight build (merge is
   the release trigger; owner-gated). Add `GoogleService-Info.plist` + APNs key to
   light up push.
3. Then the feature-authoring backlog (§4) + platform services (§5), each as its own
   verified PR.
