// IMPL-IOS-01 — `:shared:core` KMP module.
//
// PHASE 0D NOTE (2026-09-23): the spec's target versions (Kotlin 2.4 / AGP 9 /
// Room 2.8) do not exist yet — the current toolchain top is Kotlin 2.2.x. This
// build is configured to what is REAL and to what the iOS-enabling goal needs:
// verifying the shared LOGIC (domain + sync + presentation ViewModels) compiles
// and its commonTest suite passes on the JVM. That needs neither Kotlin/Native,
// Room, SKIE, nor the Android SDK, so those targets/deps are deferred behind the
// commented blocks below and re-enabled when the XCFramework / concrete DAOs are
// built. Nothing here touches `android/`.

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    // Deferred until the iOS framework / Android-consume steps:
    // alias(libs.plugins.androidLibrary)
    // alias(libs.plugins.skie)
}

kotlin {
    jvm()

    // Deferred (need Android SDK / Kotlin-Native + SKIE). Re-enable for the
    // XCFramework build once the JVM logic is green:
    //
    // androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    // listOf(iosArm64(), iosSimulatorArm64()).forEach {
    //     it.binaries.framework { baseName = "SharedCore"; isStatic = true }
    // }

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
    }
}
