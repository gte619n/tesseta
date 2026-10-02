# `shared/` — Kotlin Multiplatform core (IMPL-IOS-01)

The ONE implementation of the domain models, sync/outbox engine, collection
registry, and (Phase 1D onward) presentation state that both the Android app
and the native iOS app consume. This is decision **D1** in
[`docs/plans/IMPL-IOS-01-ios-client-parity.md`](../docs/plans/IMPL-IOS-01-ios-client-parity.md):
one core, native SwiftUI on top for iOS, existing Compose on top for Android.

## Layout

```
shared/
  settings.gradle.kts        standalone build (see "Why standalone" below)
  gradle/libs.versions.toml  Phase 0D TARGET versions (ahead of android/)
  core/
    build.gradle.kts         KMP module: jvm + androidTarget + iosArm64/iosSimulatorArm64
    src/commonMain/kotlin/com/gte619n/healthfitness/shared/
      domain/…               extracted core-domain models (@Serializable, D3)
      sync/…                  CollectionRegistry, SyncProtocol, engine/outbox interfaces
    src/commonTest/kotlin/…   CollectionRegistry + MergeConflictResolver contract tests
```

## ⚠️ Verification status

This module is **authored, not yet CI-green**. Compiling it requires the
**Phase 0D toolchain migration** (Kotlin 2.0.21 → 2.4.x line, matching KSP,
Room 2.8 KMP, Ktor 3), which is deliberately deferred so it cannot destabilize
the shipping Android build (Kotlin 2.0.21 / AGP 8.7.3). The version refs in
`gradle/libs.versions.toml` are the 0D *targets*. See decision log
`D-EXEC-1`.

Once 0D lands:

```bash
cd shared
./gradlew jvmTest                 # fast common-logic suite (also runs in ios-ci)
./gradlew iosSimulatorArm64Test   # same tests on the Apple-silicon simulator
./gradlew :core:assembleSharedCoreXCFramework   # produces the framework the iOS app embeds
```

## Why standalone (not new modules in `android/settings.gradle.kts`)

Keeping this a separate Gradle build means authoring and reviewing the shared
core does not touch the working Android build graph or its version catalog.
When Phase 0D reconciles the toolchains, `android/` will `includeBuild("../shared")`
and the Android `core-domain`/`core-data` become thin consumers of `:shared:core`.
See decision log `D-EXEC-2`.
