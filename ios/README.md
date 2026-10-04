# HealthFitness — iOS client

Native SwiftUI app (iPhone + iPad) for the HealthFitness / tesseta platform,
built on the shared Kotlin Multiplatform core. The offline-first data layer
(SQLDelight mirror + outbox + delta-sync engine + AES-GCM payload cipher) is in
place and **33 screens are bound to the shared KMP ViewModels**; the
`SharedCore.xcframework` is built and linked. Everything is compile/archive-green
but **not yet run signed-in on a device** — see the live status below.

- **Current status & remaining gaps (SSOT):** [`docs/plans/IMPL-IOS-01-STATUS.md`](../docs/plans/IMPL-IOS-01-STATUS.md)
- Plan / spec: [`docs/plans/IMPL-IOS-01-ios-client-parity.md`](../docs/plans/IMPL-IOS-01-ios-client-parity.md)
- Per-phase + per-screen decisions log: [`docs/plans/IMPL-IOS-01-OFFLINE-SYNC.md`](../docs/plans/IMPL-IOS-01-OFFLINE-SYNC.md)

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

Produce it from `shared/` **before** running `xcodegen generate` / `xcodebuild`
(the app links it as a required framework; a clean checkout has no `build/`):

```sh
cd shared
./gradlew :core:assembleSharedCoreReleaseXCFramework
# emits shared/core/build/XCFrameworks/release/SharedCore.xcframework
# (or :core:assembleSharedCoreXCFramework for both debug + release)
```

`project.yml` links it directly at
`../shared/core/build/XCFrameworks/release/SharedCore.xcframework`. The CI and
TestFlight release workflows build it in a dedicated step before xcodebuild/gym;
building locally follows the same order.

## Auth configuration (login)

Login (`GoogleSignInService` → `/api/auth/exchange`) needs two things wired, and
they are kept out of git:

1. **A Google iOS OAuth client ID.** Create one in Google Cloud Console →
   *APIs & Services → Credentials → Create OAuth client ID → iOS*, bundle id
   `com.gte619n.healthfitness`. Google shows both the client ID and its
   "iOS URL scheme" (the reversed client ID).
2. **Backend audience allow-listing.** The Google ID token's audience is this
   iOS client ID, so the backend's `OAUTH_ALLOWED_AUDIENCES` must include it
   (`backend/.../AppAuthProperties`, env `OAUTH_ALLOWED_AUDIENCES`). This is the
   same mechanism Android/web use — add the iOS id alongside the existing ones.

Set the values locally via a gitignored xcconfig:

```sh
cp ios/Config/Secrets.example.xcconfig ios/Config/Secrets.xcconfig
# edit Secrets.xcconfig: HF_GOOGLE_IOS_CLIENT_ID, HF_GOOGLE_REVERSED_CLIENT_ID,
# and optionally HF_BACKEND_BASE_URL (blank → deployed Cloud Run service).
```

`ios/Config/Auth.xcconfig` (committed, empty defaults) `#include?`s that file and
feeds the `HF_*` build settings into Info.plist + `AppConfig`. CI can instead
export `HF_*` on the `xcodebuild` command line. **No config needed to test
sign-in on the simulator** — DEBUG builds expose a "Dev sign-in (UAT)" button
(`/api/auth/dev-login`) that works against any non-prod backend with no Google
account.

## What's wired

> For the authoritative, dated breakdown of what's done vs. the remaining gaps
> (on-device verification, TestFlight/push activation, author-new-VM features),
> see [`docs/plans/IMPL-IOS-01-STATUS.md`](../docs/plans/IMPL-IOS-01-STATUS.md).
> The summary below covers the app shell + auth; the sync engine and the 33
> feature screens on shared ViewModels landed on top of it.

**App shell & auth:**
- Adaptive navigation root (`RootView`): `TabView` on compact (iPhone),
  `NavigationSplitView` on regular width (iPad) — the D16 600dp↔size-class
  breakpoint parity, with a "More" bucket on phone mirroring Android's MoreScreen.
- Design system (`DesignSystem/`): `Theme` (colors/typography mirrored 1:1 from
  Android `core-ui`), `SettingsCard` / `NavRow` / `ToggleRow` / `SegmentedChoice`,
  and the `.formMaxWidth()` (600pt) modifier.
- `KeychainTokenStore`: real Security-framework token cache with
  `kSecAttrAccessibleAfterFirstUnlock` (D6) so background sync can read tokens.
- `AuthState`: the signed-out/loading/signed-in state machine + offline-first
  cached-session launch, sign-in progress/error flags, and the presentation-only
  identity decoded from the access-token JWT.
- **Google sign-in (login)**: `GoogleSignInService` presents the Google account
  sheet, exchanges the ID token at `POST /api/auth/exchange` for a backend
  access+refresh pair (ADR-0010), persisted in `KeychainTokenStore`. `AuthApi`
  (URLSession) mirrors Android's `AuthApi` (exchange / refresh / logout /
  dev-login; 4-field `TokenResponse`). DEBUG builds also get a "Dev sign-in
  (UAT)" button → `POST /api/auth/dev-login` (no Google account; backend-gated
  to non-prod) for the simulator + E2E harness. **Config: see "Auth
  configuration" below — a real build needs a Google iOS client ID.**
- Info.plist capability keys (D7/D8 background modes + BGTask ids, D11 camera,
  D12 `healthfitness://` scheme, D6 Google OAuth URL scheme + auth config keys).
- SwiftLint config; XcodeGen spec with app + unit-test (Swift Testing) + UI-test
  (XCUITest) targets.

**Built on top of the shell (see STATUS for detail):**
- The offline-first data layer: SQLDelight mirror + outbox + delta-sync engine +
  AES-GCM `PayloadCipher`, `SyncBridge` (foreground/BGTask pull, first-sync gate),
  sign-out wipe, parked-op recovery.
- 33 `Features/*` screens bound to the shared KMP ViewModels, over the networked
  `Http*Repository` layer + `KtorSseClient` streaming transport.

**Still open (see STATUS §"What's NOT done"):** on-device verification, push (FCM)
activation, several author-new-shared-VM features, and platform services
(med-reminder scheduling, TTS coach voice).

## Layout

```
ios/
  project.yml                 XcodeGen spec (SSOT for the project)
  .swiftlint.yml
  HealthFitness/
    App/                      @main app, AppState DI, adaptive RootView
    Auth/                     AuthState, KeychainTokenStore, AppConfig, AuthApi, GoogleSignInService, SignInView
    Config/                   Auth.xcconfig (+ gitignored Secrets.xcconfig) — D6 auth config
    Sync/                     SyncBridge (stub), FirstSyncGateView ("Setting up")
    DesignSystem/             Theme + core-ui primitive equivalents
    Features/<Area>/          one SwiftUI screen stub per top-level destination
    Info.plist
  HealthFitnessTests/         Swift Testing (CollectionRegistry SKIE bridge stub)
  HealthFitnessUITests/       XCUITest smoke stub
  fastlane/                   (Phase 0E) TestFlight lane
```
