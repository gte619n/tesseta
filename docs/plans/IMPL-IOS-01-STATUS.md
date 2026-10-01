# IMPL-IOS-01 — Status & Next Steps

> Living snapshot · Updated 2026-09-28 · Branch `iphone-client` (based on
> `origin/main`). Companion to the spec
> [`IMPL-IOS-01-ios-client-parity.md`](IMPL-IOS-01-ios-client-parity.md),
> [`IMPL-IOS-01-decision-log.md`](IMPL-IOS-01-decision-log.md),
> [`ios-parity-matrix.md`](ios-parity-matrix.md),
> [`ios-parity-gap-report.md`](ios-parity-gap-report.md), and
> [`IMPL-E2E-01-synthetic-user-testing.md`](IMPL-E2E-01-synthetic-user-testing.md).

## TL;DR

The **shared brain is built, compiling, and at logic-parity with Android `main`
through #283.** The **native iOS skin is authored as scaffolds but does not build
or run yet** — it is gated on one toolchain step (Phase 0D → the XCFramework).
That single gate is the critical path to a runnable iPhone/iPad app.

## Architecture (as built)

- **`shared/`** — a standalone Kotlin Multiplatform module (`:core`): the ONE
  implementation of the domain models, sync engine, outbox, collection registry,
  repositories (interfaces), and ~50 presentation ViewModels that both Android
  and iOS consume (decision D1). 115 Kotlin files, 29 of them `commonTest`.
- **`ios/`** — native SwiftUI app (88 Swift files): XcodeGen project, adaptive
  root (iPhone `TabView` / iPad `NavigationSplitView`), all 8 feature areas as
  views, design system, real Keychain store, sync bridge, Live Activity, Vision
  capture, local-notification planner. Binds to the shared VMs via a SKIE
  `@Observable` bridge.
- **`contracts/`** — 29 golden wire fixtures (the anti-drift keystone).
- **CI** — `ios-ci.yml` (shared JVM + iOS tests, Xcode build), `release-ios-on-main.yml`
  (fastlane → TestFlight), `e2e-local.yml` (self-hosted M4 synthetic tests).

## What's DONE and VERIFIED

| Area | State |
|---|---|
| Spec + decisions (D1–D21) | ✅ Locked (owner interview) |
| Contract fixtures (25 collections + envelopes) | ✅ Authored |
| **Shared KMP module compiles on JVM** | ✅ `./gradlew :core:compileKotlinJvm` clean (Kotlin 2.2.20) |
| Sync core + convergence harness | ✅ Green (caught + fixed 2 real bugs) |
| All ~50 presentation ViewModels | ✅ Compile; affected suites green |
| iOS SwiftUI feature scaffolds (8 areas) | ✅ Authored (not yet building — see gate) |
| Concrete `KtorSseClient` (SSE transport) | ✅ Authored + compiles |
| **Android `main` logic sync** | ✅ Current through **#283** (#275–283) |
| E2E scaffold (Maestro + Playwright + runner) | ✅ Authored; Maestro parses flows; web leg runs on a clean checkout |

Sync history on this branch: foundation → Phase 3 (8 verticals) → Phase 4
(audit + convergence) → compile-green core → syncs #275–281, #282, #283.

## What's NOT done (and why)

1. **Phase 0D — iOS XCFramework → BLOCKED ON A FULL XCODE INSTALL.** The shared
   module's build is now **wired for iOS** (`shared/core/build.gradle.kts` enables
   `iosArm64`/`iosSimulatorArm64` + the `SharedCore` XCFramework; JVM still green,
   verified). The remaining step — actually compiling the iOS targets + assembling
   the XCFramework — needs the **iPhoneOS/iPhoneSimulator SDK**, which ships only
   with **full Xcode**. This machine has **Command Line Tools only** (no Xcode,
   no iOS SDK), so Kotlin/Native can't build the iOS slice and `xcodebuild` can't
   build the app. Installing Xcode (~40 GB, Apple ID + sudo) is a human/admin
   step. Turn-key finish once installed: **[`IMPL-IOS-01-0D-RUNBOOK.md`](IMPL-IOS-01-0D-RUNBOOK.md)**
   (`./gradlew :core:assembleSharedCoreXCFramework`). **This is THE gate**: until
   it lands, no `import SharedCore`, views run on local `@State` mirrors, nothing
   executes on a device.
2. **iOS UI parity** — views are scaffolds; per-feature binding to the shared VMs
   + screen-by-screen parity is tracked in `ios-parity-matrix.md` /
   `ios-parity-gap-report.md` (12 BLOCKER / ~35 SHOULD at last audit).
3. **Audit-flagged functional gaps** (from `ios-parity-gap-report.md`):
   - Sign-out does **not** wipe the mirror/outbox (PHI-leak risk) — fix before any real sign-in.
   - D9 reminder planner wiring (`plannedDoses()` returns `[]`).
   - GoogleSignIn is a stub; `dev-login` affordance not yet added.
4. **Android-consuming half of 0D** — making `android/` depend on `shared/`
   (retiring its duplicate core) is deliberately deferred behind the owner's
   D19/D20 soak gates. `android/` is currently untouched.
5. **On-device / TestFlight** — needs Apple provisioning (bundle IDs, APNs key,
   App Store Connect key) + a Mac build host.
6. **E2E execution** — web leg blocked only by the worktree `node_modules`
   symlink; mobile legs need booted devices + built apps.

## Next steps (prioritized)

**P0 — unblock everything (the critical path):**
1. **Phase 0D toolchain migration + XCFramework build.** Bump the shared module's
   Kotlin/AGP/Room to a set that compiles the iOS native targets, uncomment the
   `iosArm64`/`iosSimulatorArm64` + SKIE blocks in `shared/core/build.gradle.kts`,
   and produce `SharedCore.xcframework`. Verify `:core:iosSimulatorArm64Test`
   green. Done when `xcodegen generate && xcodebuild` builds the SwiftUI app
   against the framework.

**P1 — make it correct & signable (needs a Mac + Apple account):**
2. Fix the **sign-out mirror wipe** (PHI-leak), wire the **D9 reminder planner**
   to the shared `ReminderPlanner`, and implement **GoogleSignIn + dev-login**.
3. Apple provisioning + first **TestFlight** build via the existing fastlane lane.

**P2 — close UI parity (per the matrix):**
4. Work `ios-parity-matrix.md` row by row: bind each SwiftUI view to its shared
   VM, starting with the spine (auth → today → log meal → log workout → med
   reminder), then fan out. Each row's gaps are in `ios-parity-gap-report.md`.

**P3 — verification & synthetic testing:**
5. Run the E2E rig on the real M4 (`scripts/e2e/setup-mac.sh` →
   `run-local.sh --subset`); add Android Compose `testTag`s; grow journeys from
   the parity matrix; add a Firebase Test Lab real-device pass for RC fragmentation.

**Ongoing — keep logic parity:** on each Android `main` merge, run the sync
workflow (`git log <lastSynced>..origin/main` → diff core-domain/core-data/
ViewModels/backend → port to `shared/` → compile + test + commit). Currently
caught up through **#283**.

## How to resume / verify quickly

```bash
# shared logic (works today):
cd shared && JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew :core:jvmTest
# (if a run wedges: pkill -9 -f GradleDaemon; re-run with --no-daemon)

# next Android-main sync:
git fetch origin main && git log --oneline <lastSynced>..origin/main
```

> ⚠️ Environment caveats (this worktree, not the code): Gradle `jvmTest`
> execution can wedge after repeated runs (daemon lock contention — kill daemons,
> use `--no-daemon`); `web/node_modules` is a symlink to the main checkout (web
> E2E runs on a clean checkout only); macOS ships bash 3.2 (scripts avoid
> `set -u`).
