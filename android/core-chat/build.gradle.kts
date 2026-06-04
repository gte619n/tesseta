plugins {
    id("healthfitness.android.library")
    id("healthfitness.android.compose")
    id("healthfitness.android.hilt")
}

android {
    namespace = "com.gte619n.healthfitness.core.chat"
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-ui"))
    // For the shared OkHttpClient + @BackendBaseUrl qualifier (NetworkModule).
    implementation(project(":core-data"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // SSE: manual chunked reader over the shared OkHttpClient (assumption 17).
    implementation(libs.okhttp)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // Markdown rendering for assistant messages (assumption 17).
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
