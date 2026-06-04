plugins {
    id("healthfitness.android.library")
    id("healthfitness.android.compose")
    id("healthfitness.android.hilt")
}

android {
    namespace = "com.gte619n.healthfitness.feature.settings"
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

    implementation(libs.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3.window.size)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
