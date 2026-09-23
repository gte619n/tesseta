# HealthFitness — iOS client

Native SwiftUI app (iPhone + iPad) for the HealthFitness / tesseta platform,
built on the shared Kotlin Multiplatform core. This is **IMPL-IOS-01 Phase 2A**:
the app shell — navigation skeleton, design system, auth/sync scaffolding. Most
feature screens are stubs; the shared core is referenced but not yet built.

See the plan: [`docs/plans/IMPL-IOS-01-ios-client-parity.md`](../docs/plans/IMPL-IOS-01-ios-client-parity.md).

## Requirements

- Xcode 26+ (iOS 26 / iPadOS 26 SDK). **Floor is iOS 26, latest-major only**
  (D16) — no `@available` guards; newest SwiftUI/ActivityKit APIs used freely.
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`.
- (Optional, for linting) SwiftLint: `brew install swiftlint`.

## Generating the Xcode project

The `.xcodeproj` is **not committed** — it is generated from `project.yml`
(hand-written `.pbxproj` files are merge-hostile under multi-agent fan-out).

```sh
cd ios
xcodegen generate
open HealthFitness.xcodeproj
```

Re-run `xcodegen generate` whenever `project.yml` or the file layout changes.

## The shared core (SharedCore XCFramework)

The app embeds the KMP `shared` core (sync engine, 25-collection registry,
outbox, repositories, ViewModels) exported as an **XCFramework via SKIE**
(Touchlab) so Kotlin `Flow`/`StateFlow` surface as Swift `AsyncSequence` and
sealed classes as Swift enums (D1).

Produce it (once Phase 0D / Phase 1 land) from `shared/`:

```sh
cd shared
./gradlew :core:assembleSharedCoreXCFramework
# emits shared/core/build/XCFrameworks/release/SharedCore.xcframework
```

`project.yml` references it as a local Swift package at
`../shared/core/build/XCFrameworks/SharedCore`. Until it exists, the package
dependency lines are **commented out** in `project.yml`, and every
`import SharedCore` call site in this shell is stubbed/commented — so the shell
generates and builds on its own.

## What's real vs. stubbed in this phase (2A)

**Real, usable now:**
- Adaptive navigation root (`RootView`): `TabView` on compact (iPhone),
  `NavigationSplitView` on regular width (iPad) — the D16 600dp↔size-class
  breakpoint parity, with a "More" bucket on phone mirroring Android's MoreScreen.
- Design system (`DesignSystem/`): `Theme` (colors/typography mirrored 1:1 from
  Android `core-ui`), `SettingsCard` / `NavRow` / `ToggleRow` / `SegmentedChoice`,
  and the `.formMaxWidth()` (600pt) modifier.
- `KeychainTokenStore`: real Security-framework token cache with
  `kSecAttrAccessibleAfterFirstUnlock` (D6) so background sync can read tokens.
- `AuthState`: the signed-out/loading/signed-in state machine + offline-first
  cached-session launch.
- Info.plist capability keys (D7/D8 background modes + BGTask ids, D11 camera,
  D12 `healthfitness://` scheme).
- SwiftLint config; XcodeGen spec with app + unit-test (Swift Testing) + UI-test
  (XCUITest) targets.

**Stubbed (pins the API/shape; wiring lands in the noted phase):**
- `GoogleSignInService` (2B) — the `/api/auth/exchange` call shape is documented.
- `SyncBridge` (2C) — the SKIE Flow-collection API, foreground/push/BGTask pull.
- All `Features/*` screens — one View per top-level destination with a
  doc-comment naming its Android parity screen(s) + shared ViewModel.
- `CollectionRegistryBridgeTest` (Swift Testing) — `.disabled` until the
  XCFramework exists.

## Layout

```
ios/
  project.yml                 XcodeGen spec (SSOT for the project)
  .swiftlint.yml
  HealthFitness/
    App/                      @main app, AppState DI, adaptive RootView
    Auth/                     AuthState, KeychainTokenStore, GoogleSignInService (stub), SignInView
    Sync/                     SyncBridge (stub), FirstSyncGateView ("Setting up")
    DesignSystem/             Theme + core-ui primitive equivalents
    Features/<Area>/          one SwiftUI screen stub per top-level destination
    Info.plist
  HealthFitnessTests/         Swift Testing (CollectionRegistry SKIE bridge stub)
  HealthFitnessUITests/       XCUITest smoke stub
  fastlane/                   (Phase 0E) TestFlight lane
```
