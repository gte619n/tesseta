plugins {
    `kotlin-dsl`
}

group = "com.gte619n.healthfitness.buildlogic"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // compileOnly: these Gradle plugins are applied by the consuming modules at
    // runtime; build-logic only needs their APIs to configure them at compile time.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "healthfitness.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "healthfitness.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "healthfitness.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
    }
}
