// IMPL-IOS-01 — `:shared:core` KMP module.
//
// PHASE 0D (2026-09-28): iOS targets + XCFramework assembly are now ENABLED so
// the SwiftUI app can embed the shared core. Building the iOS targets REQUIRES a
// full Xcode install (the iPhoneOS/iPhoneSimulator SDK) — Command Line Tools
// alone are NOT enough. See docs/plans/IMPL-IOS-01-0D-RUNBOOK.md.
//
//   JVM target  → fast shared-logic tests (works with just a JDK, no Xcode).
//   iOS targets → the XCFramework the SwiftUI app links against (needs Xcode).
//
// SKIE (Swift-friendly interop: StateFlow→AsyncSequence, sealed→enum) is staged
// as step 2 — a plain Kotlin/Native framework already unblocks the app build; add
// SKIE once the native compile is proven (verify the SKIE↔Kotlin version first).
// androidTarget stays deferred — that's the separate "android/ consumes shared"
// step gated by D19/D20.

import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    // Phase 1C offline sync (IMPL-IOS-01): SQLDelight backs the on-device mirror +
    // outbox + sync-state (3 generic tables). Chosen over Room-KMP for a
    // battle-tested Kotlin/Native path; PHI payloads are app-layer encrypted
    // (PayloadCipher) so the SQLite file itself holds only non-PHI metadata.
    alias(libs.plugins.sqldelight)
    // SKIE is DISABLED: SKIE 0.10.4 (latest) predates Xcode 26 / Swift 6.3.3, and
    // the Swift overlay it emits isn't consumable by Xcode 26's build-system
    // module graph (proven: standalone swiftc loads it, Xcode never does). So we
    // ship a plain Kotlin/Native framework (ObjC-bridged clang module) and bridge
    // Flows to Swift by hand (IosComposition.collectFlow). Re-enable SKIE once it
    // supports Xcode 26.
    // alias(libs.plugins.skie)
    // Deferred (the android/-consumes-shared step, D19/D20):
    // alias(libs.plugins.androidLibrary)
}

kotlin {
    jvm()

    // Native canary: macosArm64 compiles with Kotlin/Native using the macOS SDK
    // that Command Line Tools already ship (unlike the iOS SDK, which needs full
    // Xcode). Running `:core:macosArm64Test` proves the shared commonMain is
    // Kotlin/Native-clean + its tests pass on native — the hardest part of the iOS
    // compile — WITHOUT Xcode. Keep it: the self-hosted M4 can gate native-compat
    // regressions this way even before Xcode lands.
    macosArm64()

    // The XCFramework bundles both the device + Apple-silicon-simulator slices so
    // the Xcode app can link one artifact. Build it with:
    //   ./gradlew :core:assembleSharedCoreXCFramework
    // → shared/core/build/XCFrameworks/release/SharedCore.xcframework
    val xcf = XCFramework("SharedCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SharedCore"
            // Static: a plain Kotlin/Native framework exposes everything through
            // its ObjC-bridged clang module (no Swift overlay), which the app's
            // `import SharedCore` loads directly — so static links in cleanly with
            // no embedding.
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.ktor.client.core)
            // REST repositories: JSON content negotiation over the shared Ktor client.
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // Offline mirror/outbox persistence (SQLDelight).
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // SQLDelight's JVM driver backs the sync-engine tests (in-memory DB).
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        // Ktor's Darwin engine backs the concrete KtorSseClient (+ the Phase-1C
        // network layer) on iOS; Android/JVM use the OkHttp engine. SQLDelight's
        // native driver opens the on-device SQLite mirror.
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("MirrorDatabase") {
            packageName.set("com.gte619n.healthfitness.shared.db")
        }
    }
}
