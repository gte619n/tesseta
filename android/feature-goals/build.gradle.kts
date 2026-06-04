plugins {
    id("healthfitness.android.library")
    id("healthfitness.android.compose")
    id("healthfitness.android.hilt")
}

android {
    namespace = "com.gte619n.healthfitness.feature.goals"
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-chat"))

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit)
}
