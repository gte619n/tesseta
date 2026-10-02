// IMPL-IOS-01 Phase 1 — the KMP shared core (`shared/`), a standalone Gradle
// build kept OUT of the Android build (`android/`) until the Phase 0D toolchain
// ride (Kotlin 2.4 / AGP 9 / Room 2.8) lands. Keeping it standalone means the
// working Android build (Kotlin 2.0.21 / AGP 8.7.3) is not perturbed while the
// shared modules are authored and reviewed. When 0D completes, `android/` will
// `includeBuild("../shared")` (or the modules move under one settings file) and
// the Android `core-domain`/`core-data` become thin consumers of `:shared`.
//
// See docs/plans/IMPL-IOS-01-decision-log.md (D-EXEC-2) for why this is a
// separate build rather than new modules inside android/settings.gradle.kts.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "shared"
include(":core")
