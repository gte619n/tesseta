// IMPL-IOS-01 Phase 1 — `:shared:core`, the Kotlin Multiplatform module that
// carries the ONE implementation of the domain models, the sync/outbox engine,
// the collection registry, and the shared presentation state that both the
// Android app and the native iOS app consume (D1).
//
// Targets:
//   - jvm()                 → runs the full commonTest suite fast in CI (ubuntu)
//   - androidTarget()       → what the Android app links against
//   - iosArm64 / iosSimulatorArm64 → the device + Apple-silicon simulator; the
//                             XCFramework the SwiftUI app embeds via SKIE (D1)
//
// ⚠️ VERIFICATION STATUS (see decision log D-EXEC-1): this module is AUTHORED
// but not yet CI-green. Compiling it requires the Phase 0D toolchain migration
// (Kotlin 2.0.21→2.4.x, matching KSP, Room 2.8 KMP) which is deliberately
// deferred so it does not destabilize the shipping Android build. The version
// refs below are the 0D TARGET versions, not the repo's current ones.

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.skie)
}

kotlin {
    jvm()

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    val xcfName = "SharedCore"
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = xcfName
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            // androidx.lifecycle ViewModel + Room ship KMP artifacts as of the
            // 0D target versions; shared ViewModels (D2) and the mirror DB
            // (D5, Room KMP) live in commonMain.
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "com.gte619n.healthfitness.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
}
