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
    // Step 2 (after a plain native compile is green on a real Xcode host):
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
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // Ktor's Darwin engine backs the concrete KtorSseClient (+ the Phase-1C
        // network layer) on iOS; Android/JVM use the OkHttp engine.
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
