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

    // IMPL-AND-* : JVM unit tests for mappers, repositories (MockWebServer), and
    // the Google Health scope flow.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.retrofit.moshi)
    testImplementation(libs.moshi.kotlin)
}
