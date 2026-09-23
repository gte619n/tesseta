# IMPL-IOS-01 — Native iOS Client (iPhone + iPad) at Android Parity

> Status: **planned** · Created 2026-09-22 · Source: owner request ("iOS client
> at functional parity with the Android client, multi-agent buildable") +
> [`ios-client-strategy.md`](ios-client-strategy.md) (this spec supersedes its
> "no build committed" status — the owner's direct request replaces the PWA
> demand-signal gate; the technical prerequisites it names are kept as Phase 0).

## Goal

Ship a native iOS app (iPhone + iPad) with functional parity to the Android
client: offline-first mirrored data for all 25 synced collections, outbox
writes, all 8 feature areas (~44 screens), medication reminders with
actionable notifications, camera meal capture with on-device barcode/OCR,
active-workout live session with rest timer, push-triggered sync, and adaptive
tablet layouts — against the existing backend with **zero backend API redesign**
(additive changes only).

## Non-goals

- watchOS app (the Android Wear app is itself a stub).
- App Store public release (TestFlight internal distribution only — this
  defers the Sign in with Apple requirement, see D13).
- HealthKit ingestion (Android parity does not require it: `dailyMetrics` /
  `bodyComposition` are server-mirrored from Google Health + Withings; the
  client only reads mirrors. Optional follow-on: IMPL-IOS-02).
- Widgets, Siri intents, App Clips.
- Rewriting the Android UI in Compose Multiplatform. Android UI stays as-is.

## Baseline (what parity means, measured)

From the 2026-09-22 codebase audit of `android/`:

| Dimension | Android today |
|---|---|
| Modules | 14 (app, wear, core-{domain,data,ui,chat}, 7 feature modules) |
| Scale | ~470 files / ~63k LOC, 53 ViewModels, 44+ screens |
| Synced collections | 25 mirror tables, `CollectionRegistry` with slash-form aliases |
| Sync | Delta pull `GET /api/me/sync` (cursor, LWW, tombstones, schemaVersion), outbox replay with `Idempotency-Key` + client-minted IDs + `X-HF-Origin-Device` |
| Background | WorkManager (SyncWorker, PeriodicSync ~6h, OutboxDrain, NutritionOp, ReminderPlan), AlarmManager (due + midnight), WorkoutSessionService foreground service |
| Push | FCM data messages: `sync`, `gh-reconnect`, `leftover-review/retake`, `adjust-review/failed` |
| Auth | Google Credential Manager → `/api/auth/exchange` → HS256 access + rotating refresh (ADR-0010/0019), offline-first cached-session launch |
| Storage | SQLCipher Room, payloadJson mirror rows, DataStore token cache |
| Tests | 102 JVM unit test files + 6 instrumented; backend 100+ unit + Firestore-emulator suite |

The backend needs nothing structural: all clients speak REST + session tokens
(ADR-0010) and the delta-sync contract is client-agnostic.

## Architecture decision (D1): KMP shared core + native SwiftUI

**Chosen: Option A-hybrid.** Kotlin Multiplatform for `core-domain`,
`core-data` (sync engine, outbox, repositories, auth plumbing), and shared
ViewModels; **native SwiftUI** for all iOS UI (no Compose Multiplatform).

Why this over the two pure options in `ios-client-strategy.md`:

- **vs. Option A (CMP UI):** the strategy doc's lead argument against A is
  "Compose-rendered iOS UI is not native-feeling; HIG conformance is manual."
  The owner asked for a real iPhone/iPad client. SwiftUI views on top of the
  shared core keep the native feel and the iPad story while still getting the
  single sync engine.
- **vs. Option B (full native Swift):** a third hand-written implementation of
  the sync engine, 25-collection registry, LWW, outbox, and nutrition math is
  exactly the contract-drift bug class (ARCH-002, XPLAT-001/004/008) that has
  already shipped bugs twice with only 2.5 client surfaces. Multi-agent
  implementation makes drift *more* likely, not less, unless the logic is
  physically shared. Sharing ViewModels also collapses the biggest parity
  surface: 53 ViewModels become "write SwiftUI views only" on iOS.
- The strategy doc already classifies phases A0–A2 as no-regret for Android
  alone (toolchain ride MIG-001, SQLCipher exit SUP-003).

Swift interop: the shared core is exported as an XCFramework with
**SKIE** (Touchlab) so `StateFlow`/`Flow` surface as Swift `AsyncSequence` and
sealed classes as Swift enums. SwiftUI observes shared ViewModels through a
thin `@Observable` bridge per screen.

## Decisions log

| # | Decision | Notes |
|---|---|---|
| D1 | KMP shared core (domain, data/sync, ViewModels) + native SwiftUI UI | See above. Supersedes strategy-doc CMP-UI assumption. |
| D2 | Shared presentation: androidx ViewModel (KMP) + StateFlow; SwiftUI observes via SKIE | VMs that touch Android APIs (camera, notifications) split into shared state-holder + platform delegate `expect/actual`. |
| D3 | Serialization in shared code: kotlinx.serialization (replaces Moshi in `core-domain`/`core-data`) | Moshi is JVM-only. Codec swap is gated by the ARCH-002 contract fixtures (Phase 0) so wire compatibility is proven, not assumed. |
| D4 | Networking: Ktor client (OkHttp engine on Android, Darwin engine on iOS) replaces Retrofit in shared repositories | Auth interceptor/authenticator behavior (silent refresh on 401, reuse-grace tolerance) ported as Ktor plugins with the existing tests carried over. |
| D5 | Storage: Room KMP (2.8+, bundled SQLite driver); encryption at rest = platform (iOS `NSFileProtectionComplete`; Android relies on FBE, minSdk 29) — retires `net.zetetic` SQLCipher (closes SUP-003) | ⚠️ Changes Android's at-rest posture from app-layer SQLCipher to OS file-based encryption. **Owner sign-off required before Phase 1.** Fallback if rejected: SQLDelight + SQLCipher drivers on both platforms (adds a DAO-layer rewrite, +4–6 d). |
| D6 | iOS auth: GoogleSignIn iOS SDK → `/api/auth/exchange`; tokens in Keychain (`kSecAttrAccessibleAfterFirstUnlock` so background sync can read them); offline-first cached-session launch identical to Android | Backend: add the new iOS OAuth client ID to `OAUTH_ALLOWED_AUDIENCES` (config-only). |
| D7 | Push: Firebase iOS SDK (FCM→APNs). Backend and `PUT /api/me/devices/fcm` registry unchanged | Silent `sync` messages arrive as `content-available` pushes — iOS throttles these (no delivery guarantee, ~budgeted per hour). Mitigation: foreground-activation pull + BGAppRefreshTask floor + user-visible pushes (leftover/adjust) carry their own sync trigger. |
| D8 | Background execution: BGAppRefreshTask (periodic delta pull, best-effort) + BGProcessingTask (outbox drain on connectivity) + always pull on foreground activation | Accept weaker guarantees than WorkManager; the offline-first read model means staleness is cosmetic, and outbox drains on next open at worst. |
| D9 | Medication reminders: UNUserNotificationCenter **pre-scheduled local notifications** (calendar triggers) planned ~48h ahead from the mirror; re-planned on every sync apply, app foreground, and a midnight/BGTask refresh. Categories give Take / Snooze / Dismiss actions; deep link to dose checklist | Deliberately *not* a port of the AlarmManager fire-time engine — iOS has no exact-alarm equivalent. The ReminderEngine planning logic (which doses, what times, carryover) stays in shared KMP; only delivery is platform. |
| D10 | Active workout: ActivityKit **Live Activity** (lock screen + Dynamic Island) replaces the foreground-service chronometer notification; in-app rest overlay keyed to a single self-ticking source (per the Android rest-timer lesson); rest-complete beep via AVAudioPlayer | Session draft persistence + timer state stay in shared KMP (`WorkoutSessionDraft` row), so kill/restore behavior matches Android. |
| D11 | Camera/CV: AVFoundation capture + Vision (`VNDetectBarcodesRequest`, `VNRecognizeTextRequest`) for barcode/label OCR — parity with CameraX + MLKit | Upload/op-rail (PendingNutritionOp states) is shared KMP. |
| D12 | Deep links: keep `healthfitness://` custom scheme (dose-checklist, nutrition-adjust-review, withings-callback) for parity; universal links deferred | Withings OAuth uses ASWebAuthenticationSession with the scheme callback. |
| D13 | Distribution: TestFlight internal testing only (parity with Firebase App Distribution internal-testers). No App Store submission in v1 | Defers the Sign in with Apple mandate (App Review 4.8 applies to App Store distribution). If v2 goes to the App Store, backend gains an `apple` issuer in `/exchange` — flagged now, not built now. |
| D14 | CI: GitHub Actions `macos-15` runners; `ios-ci.yml` mirrors the android-ci pattern (paths-filter guard, parallel jobs, aggregate gate). Release lane also GitHub Actions + fastlane (Cloud Build offers no macOS) | Build number = `git rev-list --count HEAD` (CICD-003 parity); release notes via Gemini reusing the Android lane's script. |
| D15 | Signing: App Store Connect API key + fastlane (cert/profile sync via `match` backed by a GCS bucket, consistent with the repo's GCS habit); secrets mirrored into GitHub Actions secrets from Secret Manager | One-time human setup (owner): Apple Developer enrollment, bundle IDs, Firebase iOS app + APNs key upload. |
| D16 | Minimum OS: iOS 17 / iPadOS 17 (Observation framework, mature ActivityKit; ~95% device coverage in 2026). iPad = same app, size-class adaptive (Android's 600dp breakpoint ↔ `.regular` horizontal size class) | |
| D17 | XPLAT-002 min-client-version handshake ships **before** the first iOS TestFlight build | Three continuously-deployed clients without version negotiation is not survivable. |
| D18 | iOS repo layout: `ios/` (Xcode project, SwiftUI, fastlane) + `shared/` (KMP modules extracted from `android/core-*`); Android modules consume `shared/` unchanged in behavior | Keeps paths-filter CI guards clean: `ios/**`, `shared/**`. |

## Phased plan

Effort figures are calendar-shaped for a multi-agent build: wall-clock
compresses inside phases (wide fan-out), but the phase *gates* are serial.
Solo-week figures from the strategy doc are kept as a sanity anchor.

### Phase 0 — Prerequisites & bootstrap (parallel; gate for everything)

All four are independent and agent-parallelizable; 0E has human-only steps.

| WS | Work | Definition of done |
|---|---|---|
| 0A | **ARCH-002 contract fixtures**: golden JSON fixtures for every synced collection payload + sync envelope + auth/token DTOs + WriteResult, committed under `contracts/fixtures/`. Backend test serializes real DTOs against them; Android test deserializes them through the current Moshi codecs | Both suites red-green proven (mutate a field → both fail); wired into backend-ci and android-ci |
| 0B | **XPLAT-002 version negotiation**: `X-Client` / min-version handshake endpoint + 426-style upgrade signal; Android adopts | Backend + Android shipped; contract fixture added |
| 0C | **XPLAT-001 day-key fix**: one canonical "today" (`?date=` + `X-Timezone`) across backend/web/Android | Audit finding closed; fixture added |
| 0D | **A0 toolchain ride (MIG-001)**: Kotlin 2.4.x, AGP 9.x, Room 2.8.x, Compose BOM current — Android alone, shipped and stable | android-ci green 2 weeks, no runtime regressions |
| 0E | **Apple/CI bootstrap**: Apple Developer enrollment 👤, bundle IDs, Firebase iOS app + APNs auth key 👤, App Store Connect API key 👤, fastlane match store, GitHub `macos-15` smoke workflow (empty SwiftUI app builds + uploads to TestFlight) | A signed hello-world reaches TestFlight from CI |

👤 = owner-in-the-loop step; everything else is agent-executable.

### Phase 1 — Shared core extraction (the critical path)

Sequenced; 1C fans out per-domain after the engine core lands.

| WS | Work | Definition of done |
|---|---|---|
| 1A | `core-domain` → KMP `shared/domain`: pure models, units, macros math, Moshi→kotlinx.serialization | All existing unit tests pass from `commonTest`; contract fixtures (0A) pass through the **new** codec on JVM — this is the codec-swap proof |
| 1B | Storage swap per D5: Room KMP, retire SQLCipher (Android migration path: decrypt-copy on first launch, tested against a real device DB) | Instrumented migration test green; Android ships and soaks 1 week |
| 1C | `core-data` → KMP `shared/data`: CollectionRegistry (all 25 collections + slash aliases), mirror entities/DAOs, SyncEngine (cursor, LWW MergeConflictResolver, schemaVersion resync), Outbox + replay client (Idempotency-Key, client IDs, origin-device header), Ktor network layer, auth token plumbing (silent refresh, reuse-grace), repositories per domain. Fan-out after engine core: one agent per domain repository cluster (meds, nutrition, workouts, health, goals, misc) | Full core-data unit suite (39+ files) ported to `commonTest` and green on **both** JVM and `iosSimulatorArm64` targets; Android app consumes `shared/*` with zero behavior diff (existing android-ci suite is the regression harness) |
| 1D | Shared ViewModels: extract the ~40 pure state-holder VMs to `shared/presentation` (KMP ViewModel); platform-coupled VMs (camera, notifications, TTS) split state-holder vs. `expect/actual` delegate | Android screens re-wired to shared VMs, all VM unit tests in commonTest, android-ci green |

**Gate:** Android release built entirely on `shared/` soaks on the owner's
device for a week with no sync regressions before Phase 2 UI fan-out.

### Phase 2 — iOS app shell

| WS | Work |
|---|---|
| 2A | Xcode project under `ios/`, SPM workspace, KMP XCFramework + SKIE integration, DI wiring, navigation skeleton (all routes stubbed), design-system primitives mirroring `core-ui` (theme, typography, SettingsCard/NavRow/ToggleRow/SegmentedChoice equivalents, 600dp↔size-class form max-width) |
| 2B | Auth: GoogleSignIn → `/exchange`, Keychain token cache, AuthCoordinator bridge, offline-first cached-session launch, sign-out wipe (SignOutSideEffects parity — account-switch data leak class is a known trap) |
| 2C | Sync on-device: FCM token registration (`PUT /api/me/devices/fcm`), silent-push → pull, BGAppRefreshTask/BGProcessingTask registration, FirstSyncGate ("Setting up") screen, sync-log debug screen |
| 2D | `ios-ci.yml` (see CI/CD) + fastlane TestFlight lane live on every main merge |

**Gate:** TestFlight build on a real iPhone: sign in → full initial sync of
owner's production account → dashboard renders mirrored data → offline
relaunch works.

### Phase 3 — Feature parity waves (maximum fan-out)

One agent per row; within a wave rows are independent. Each row's contract:
shared VM already exists (Phase 1D) → deliverable is SwiftUI views + platform
services + tests + parity-checklist sign-off.

| Wave | Scope (Android source of truth) | Platform-specific work |
|---|---|---|
| A1 | Dashboard/Today (PhoneTodayScreen, dashlets), More/directory | iPad split layout |
| A2 | Settings: hub, profile, drink settings, device connections (Withings OAuth via ASWebAuthenticationSession), sync diagnostics | Withings callback scheme |
| B | Medications: list/add/detail, reminder settings, today's doses | D9 local-notification planner, UNNotificationCategory actions (Take/Snooze/Dismiss), dose-checklist deep link, midnight refresh |
| C | Nutrition: today view, capture (camera + barcode/OCR), targets, entry edit/portion, adjust-with-AI flows, leftover flows, saved meals/relog, serving hints | AVFoundation+Vision, photo upload op-rail, notification deep links (adjust-review, leftover-review/retake), Apply notification actions |
| D | Workouts (largest — split into 3 agent tasks): (i) hub/programs/calendar/detail/history/library, (ii) live session: set logging, rest timer, Live Activity, recap, RIR/effort capture, (iii) designer SSE chat, progression console, gyms CRUD | ActivityKit, chronometer, audio cue, SSE client |
| E1 | Blood: overview, add reading, marker detail, lab report upload | Document camera |
| E2 | Body composition: trends, DEXA detail, upload | PDF upload |
| E3 | Goals: list, roadmap, goal chat (SSE + markdown) | SSE client reuse from D(iii) |
| F | iPad adaptive pass across all screens + polish (navigation split view, multitasking sizes) + accessibility (Dynamic Type, VoiceOver smoke) | |

Waves A→C ship to TestFlight incrementally; D–F follow. Every wave merge runs
the parity auditor (below).

### Phase 4 — Parity verification & hardening

- **Screen-by-screen parity audit** (multi-agent): for each of the 44 Android
  screens, an auditor agent compares Android screen behavior (from source) to
  the iOS implementation and files gaps against the parity matrix. Loop until
  two consecutive audit rounds find nothing new.
- **Cross-client sync convergence harness**: script boots backend +
  Firestore emulator (`dev-login` enabled), runs scripted mutations from the
  iOS simulator suite and the Android emulator suite against the same user,
  asserts mirror convergence incl. LWW conflicts, tombstones, and
  schemaVersion-bump resync.
- Performance: cold start budget (~Android's ~430ms release anchor), sync p50
  vs the 1.76s warm Android baseline, memory on iPad.
- Failure-mode drills: token family rotation race during outbox replay
  (reuse-grace), 404-on-DELETE-is-success, kill mid-sync, airplane-mode CRUD →
  reconnect.
- Owner acceptance on real iPhone + iPad.

## CI/CD

### `ios-ci.yml` (PR + main; mirrors android-ci patterns)

```
changes (paths-filter: ios/**, shared/**, .github/workflows/ios-ci.yml)
├─ shared-tests   (ubuntu):  ./gradlew :shared:allTests jvm targets + Android consumers' testDebugUnitTest
├─ shared-ios     (macos-15): ./gradlew :shared:iosSimulatorArm64Test  (KMP native target)
├─ build-test     (macos-15): xcodebuild test -scheme HealthFitness
│                   destinations: iPhone 16 sim + iPad Pro 11" sim
│                   (Swift Testing units + snapshot tests)
├─ ui-smoke       (macos-15, main + label-gated on PRs): XCUITest smoke suite
│                   against dev-login backend stub
└─ ios-ci         (aggregate gate for branch ruleset — CICD-001 pattern)
```

- Concurrency: cancel in-flight PR runs; main never cancels (parity with android-ci).
- Caches: Gradle (KMP), SPM, DerivedData (best-effort).
- android-ci additionally picks up `shared/**` in its paths filter from Phase 1
  onward (shared code regressions must gate both).
- Contract-fixture suites from 0A run in backend-ci and in `shared-tests` —
  one codec, one suite, covers both mobile platforms.

### Release: `release-ios-on-main.yml` (GitHub Actions, not Cloud Build)

Cloud Build has no macOS pool, so the iOS lane is the one GH-Actions deploy:
paths `ios/**`, `shared/**` on main → fastlane: fetch certs (match/GCS),
`git rev-list --count HEAD` build number, `xcodebuild archive`, upload to
TestFlight (internal-testers group), Gemini release notes (reuse Android
script), 5-attempt retry on upload (CICD parity), record last-release-sha.
Secrets: App Store Connect API key + match passphrase in GH Actions secrets;
canonical copies in GCP Secret Manager.

### Version scheme

`CFBundleShortVersionString = 1.<rev-count>`, `CFBundleVersion = <rev-count>`
— same monotonic source as Android versionCode.

## Test plan

| Layer | Suite | Where it runs |
|---|---|---|
| Contract | Golden-fixture round-trip for all 25 collection payloads, sync envelope, auth DTOs, WriteResult, error envelope | backend-ci (serialize) + shared-tests (deserialize) — the anti-drift keystone |
| Shared unit (commonTest) | Ported core-domain (8 files), core-data (39+: SyncEngine LWW, cursor, schemaVersion resync, outbox replay incl. 409/401/retry, registry alias resolution incl. slash-forms, token refresh + reuse-grace, repositories), shared VMs (~50 suites) | JVM in shared-tests **and** iosSimulatorArm64 in shared-ios — same tests, both runtimes |
| iOS unit (Swift Testing) | Reminder local-notification planner (48h window, carryover, midnight replan), notification category/action routing, deep-link router, Keychain token cache, BGTask scheduling policy, Live Activity state mapping, Vision barcode/OCR result mapping, size-class layout logic | build-test |
| Snapshot | Per-screen SwiftUI snapshots (pointfreeco swift-snapshot-testing), iPhone + iPad + Dynamic Type XL variants | build-test |
| XCUITest smoke | dev-login → first sync gate → dashboard; add med + mark dose taken from notification; manual meal log; start workout session → log set → rest timer → complete; offline relaunch renders mirrors | ui-smoke |
| Cross-client E2E | Convergence harness (Phase 4): iOS sim + Android emulator against one backend/emulator user — LWW, tombstones, resync bump, outbox drain after airplane mode | scheduled/nightly + pre-release |
| Migration | Android SQLCipher→Room-KMP decrypt-copy migration on a real device DB snapshot | android instrumented (Phase 1B gate) |
| Manual device pass | Push delivery (silent + visible), Live Activity on lock screen/Dynamic Island, camera capture in low light, background refresh overnight, iPad multitasking | owner, per TestFlight build |

## Multi-agent execution plan

### Roles

- **Implementer agents** — one per workstream row above. Each receives a task
  card (below) and works in an isolated worktree/branch; one PR per
  workstream.
- **Contract guard** — owns `contracts/fixtures/`; the only agent allowed to
  change fixtures, and only with a paired backend+client PR. Every other
  agent's PR must leave fixtures untouched.
- **Parity auditor** — per wave: reads the Android screen source (source of
  truth), diffs behavior against the iOS PR, files gap findings. Adversarial:
  prompted to find missing behavior, not to confirm parity.
- **Reviewer** — `/code-review` on every PR; suites green is a merge gate, not
  a goal.

### Task card template (per workstream)

```
Scope:        <workstream id + one paragraph>
Source of truth: <Android files/screens, spec sections, ADRs>
Interface:    <shared-core APIs consumed; may NOT modify shared/ unless the
               card says so — file an interface-change request instead>
Done when:    <tests listed in the test plan for this row are green; parity
               checklist rows checked; ios-ci green>
Forbidden:    contracts/fixtures/**, other workstreams' directories,
               backend/** (unless card grants it)
```

### Dependency DAG & concurrency

```
Phase 0:  0A ∥ 0B ∥ 0C ∥ 0D ∥ 0E                     (5 agents + owner for 0E 👤)
Phase 1:  0A→1A → 1B(👤 sign-off on D5) → 1C-core → 1C-domains ×6 ∥ → 1D ×5
Phase 2:  2A → (2B ∥ 2C ∥ 2D)                        (shell first, then 3 agents)
Phase 3:  Wave A ×2 ∥ B ∥ C ∥ D ×3 ∥ E ×3  → F      (up to ~10 agents at peak)
Phase 4:  auditors ×N (loop-until-dry) ∥ E2E harness ∥ perf
```

Freeze points: wire contract frozen at end of Phase 0 (changes go through the
contract guard); `shared/` public API frozen per-domain at end of Phase 1
(Phase 3 agents consume, never modify); design-system primitives frozen at end
of 2A.

### Coordination rules

1. The **parity matrix** (`docs/plans/ios-parity-matrix.md`, generated at
   Phase 3 kickoff from the Android screen inventory in this spec's baseline)
   is the single checklist; every Phase 3/4 agent updates its rows in its PR.
2. Agents never share a branch. Interface changes to `shared/` during Phase 3
   are escalated, batched, and land as their own PR before dependents rebase.
3. Every phase gate is a human-visible checkpoint: android-ci + ios-ci +
   backend-ci green, soak criteria met, owner sign-off where marked 👤.

## Backend changes (small, additive — all in Phase 0/2)

1. `OAUTH_ALLOWED_AUDIENCES` += iOS OAuth client ID (config).
2. XPLAT-002 version-negotiation endpoint (0B).
3. XPLAT-001 day-key canonicalization (0C).
4. Contract-fixture test suite (0A).
5. Verify FCM fan-out works for APNs-backed tokens (`content-available` for
   the silent `sync` type — likely a per-platform message option in the
   publisher; the token registry itself is platform-agnostic).

## Effort & risk

| Phase | Agent-parallel calendar estimate | Serial-solo anchor |
|---|---|---|
| 0 | ~1 week (0E has Apple-side human latency) | 2–3 wk |
| 1 | 3–4 weeks (critical path: 1A→1B→1C-core) | 6–8 wk |
| 2 | 1–2 weeks | 3 wk |
| 3 | 3–4 weeks at ~10-agent fan-out | 10–14 wk |
| 4 | 1–2 weeks (audit loop) | 2–3 wk |
| **Total** | **~9–13 weeks calendar** | ~6 months solo |

Top risks, with mitigations:

1. **Phase 1 destabilizes the working Android app** (biggest). Mitigation:
   android-ci as regression harness at every step, 1-week soak gates on 1B and
   end-of-1, behavior-diff-zero rule, `interactive_workout`-style abandoned
   branches avoided by landing each WS to main behind the soak gates.
2. **D5 encryption posture change rejected** → fallback SQLDelight+SQLCipher
   (+4–6 d, DAO rewrite). Decide before 1B starts.
3. **iOS silent-push throttling makes sync feel stale** → measured in Phase 2
   gate; if bad, lean harder on foreground pull + user-visible pushes (which
   are not throttled the same way).
4. **KMP/Swift interop friction** (closures, generics, flows) → SKIE from day
   one; 2A includes an interop spike that exercises the worst shapes (sealed
   results, paging, flows-of-lists) before fan-out.
5. **Kotlin/AGP/Room toolchain ride (0D) stalls** → it's a standalone
   no-regret workstream; the rest of Phase 0 proceeds regardless.
6. **Apple-side human latency** (enrollment, APNs keys, TestFlight review) →
   0E starts day one; nothing else blocks on it until Phase 2's gate.

## Kickoff checklist (first multi-agent batch)

- [ ] Owner: D5 encryption sign-off; start Apple Developer enrollment (0E 👤)
- [ ] Agent 1: WS-0A contract fixtures (backend + Android)
- [ ] Agent 2: WS-0B version negotiation
- [ ] Agent 3: WS-0C day-key fix
- [ ] Agent 4: WS-0D toolchain migration (long-running)
- [ ] Agent 5: WS-0E CI half — GH macos workflow skeleton + fastlane scaffold
      (blocks on owner's Apple artifacts only at the upload step)
