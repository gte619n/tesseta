plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gte619n.healthfitness.domain"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // Goals (IMPL-12): domain models carry Moshi annotations so they double as
    // the JSON wire contract with the backend.
    api(libs.moshi)

    // IMPL-AND-* : pure-Kotlin domain unit tests (formatters, mappers, helpers).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
