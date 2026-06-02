plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gte619n.healthfitness.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // IMPL-AND-20: export Room schemas so migrations can be diffed in CI.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
    // The exported Room schema JSONs are test fixtures for the instrumented
    // migration tests; make them visible to the androidTest source set.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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
    implementation(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // IMPL-AND-20 (offline-first sync, Phase 3): SQLCipher-encrypted Room DB.
    // SupportFactory (sqlite-ktx) opens Room with the Keystore-derived
    // passphrase. WorkManager is wired now for the Phase 4 sync workers.
    implementation(libs.sqlcipher.android)
    implementation(libs.sqlite.ktx)
    implementation(libs.work.runtime.ktx)

    implementation(libs.datastore.preferences)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // IMPL-02: Credential Manager + Google ID for Google sign-in on phone.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id)

    // IMPL-AND-02: AuthorizationClient (incremental Google Health scope) +
    // Task.await() bridge.
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    // IMPL-12: Hilt — network DI module + repositories.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // IMPL-AND-20 (Phase 4): Hilt + WorkManager integration so the sync workers
    // (@HiltWorker) can inject SyncEngine/OutboxRepository.
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // IMPL-AND-* : JVM unit tests for mappers, repositories (MockWebServer), and
    // the Google Health scope flow.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.retrofit.moshi)
    testImplementation(libs.moshi.kotlin)

    // IMPL-AND-20 (Phase 3): instrumented Room + SQLCipher DAO round-trip tests.
    // These require an emulator/device (no JVM Room) — run via
    // `:core-data:connectedDebugAndroidTest` in CI, not in this sandbox.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.sqlcipher.android)
    androidTestImplementation(libs.sqlite.ktx)
    androidTestImplementation(libs.moshi)
    androidTestImplementation(libs.moshi.kotlin)

    // IMPL-AND-20 (Phase 7): on-device E2E + convergence tests drive a real
    // SQLCipher Room DB + sync engine against a MockWebServer standing in for the
    // backend (offline CRUD → reconnect → server state; two-client LWW; tombstone
    // apply). Require an emulator/device — run via `:core-data:connectedDebugAndroidTest`.
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.retrofit)
    androidTestImplementation(libs.retrofit.moshi)
}
