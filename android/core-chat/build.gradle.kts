plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gte619n.healthfitness.core.chat"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-ui"))
    // For the shared OkHttpClient + @BackendBaseUrl qualifier (NetworkModule).
    implementation(project(":core-data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // SSE: manual chunked reader over the shared OkHttpClient (assumption 17).
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // Markdown rendering for assistant messages (assumption 17).
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
