# Phase 0D runbook — build the iOS XCFramework

> Created 2026-09-28 · The one gate to a runnable iPhone/iPad app. The shared
> module's build is already wired for iOS (`shared/core/build.gradle.kts` has the
> `iosArm64`/`iosSimulatorArm64` targets + the `SharedCore` XCFramework). What
> remains needs a **full Xcode install**, which this machine does not have yet.

## Why this is blocked right now

`xcode-select -p` points at `/Library/Developer/CommandLineTools`. CLT ships the
**macOS** SDK only — there is **no iPhoneOS/iPhoneSimulator SDK**, and Kotlin/
Native cannot compile the iOS targets (nor can `xcodebuild` build the app)
without it. This is an environment prerequisite, not a code problem: the JVM
build of the shared logic is green; only the iOS slice is gated.

## Step 0 — install Xcode (human/admin; ~40 GB, needs Apple ID + sudo)

Pick one:
```bash
# Option A — App Store: search "Xcode", install (GUI, Apple ID).
# Option B — xcodes CLI:
brew install xcodes
xcodes install --latest          # interactive Apple auth; downloads ~40 GB
```
Then point the toolchain at it and accept the license:
```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -license accept
xcodebuild -runFirstLaunch
xcodebuild -showsdks | grep -i iphone   # must now list iphoneos / iphonesimulator
```

## Step 1 — compile the shared core for iOS + run native tests

```bash
cd shared
export JAVA_HOME=~/.sdkman/candidates/java/current
# First run downloads the Kotlin/Native compiler + platform libs (~1 GB, slow).
./gradlew :core:iosSimulatorArm64Test       # the commonTest suite on the iOS simulator target
```
Expect: the same suites that pass on JVM pass here. Any failure is almost
certainly a commonMain API that isn't multiplatform — the domain was written to
avoid that (kotlinx-datetime, no `java.*`), so this should be clean. Fix any
stragglers, re-run.

## Step 2 — assemble the XCFramework

```bash
./gradlew :core:assembleSharedCoreXCFramework
# → shared/core/build/XCFrameworks/release/SharedCore.xcframework
```

## Step 3 — wire it into the iOS app

1. In `ios/project.yml`, uncomment the `SharedCore` binary/package dependency and
   point it at the built `SharedCore.xcframework` (or a local SPM wrapper).
2. Across `ios/HealthFitness/**`, uncomment `import SharedCore`, delete the local
   `ScreenState`/mirror enums and the `// Post-0D` stubbed `.task { vm.observe(...) }`
   bindings, and bind each view to its real shared ViewModel.
3. Generate + build:
   ```bash
   cd ios && xcodegen generate
   xcodebuild -scheme HealthFitness -destination 'generic/platform=iOS Simulator' build
   ```

## Step 4 — add SKIE (interop ergonomics)

A plain Kotlin/Native framework works, but exposes Kotlin `StateFlow`/sealed
classes awkwardly to Swift. SKIE fixes that (StateFlow→`AsyncSequence`,
sealed→Swift `enum`), which the `ObservableViewModel` bridge assumes.

1. Uncomment `alias(libs.plugins.skie)` in `shared/core/build.gradle.kts`.
2. **Verify the SKIE↔Kotlin version** (`skie` in `shared/gradle/libs.versions.toml`
   is pinned at 0.10.1 against Kotlin 2.2.20 — confirm that pairing on SKIE's
   compatibility matrix; bump if needed).
3. Re-run Step 2, then replace the local mirror enums in the Swift views with the
   SKIE-bridged shared enums (switch directly on `MedicationsUiState`, etc.).

## After 0D — the follow-on work (see IMPL-IOS-01-STATUS.md)

Once the app builds against the framework: fix the sign-out mirror wipe, wire the
D9 reminder planner + GoogleSignIn/dev-login, then work `ios-parity-matrix.md`
row by row. Apple provisioning (bundle ids, APNs key, App Store Connect key)
unlocks the first TestFlight via the existing fastlane lane.
