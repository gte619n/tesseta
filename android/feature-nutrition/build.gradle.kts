plugins {
    id("healthfitness.android.library")
    id("healthfitness.android.compose")
    id("healthfitness.android.hilt")
}

android {
    namespace = "com.gte619n.healthfitness.feature.nutrition"
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-ui"))
    implementation(project(":core-data"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.navigation.compose)

    // IMPL-13 — food studio image display.
    implementation(libs.coil.compose)

    // IMPL-13 — camera capture + on-device barcode scanning.
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    // Unified capture: on-device OCR to auto-detect nutrition labels in the feed.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
