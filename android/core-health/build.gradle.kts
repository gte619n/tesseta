plugins {
    id("healthfitness.android.library")
}

android {
    namespace = "com.gte619n.healthfitness.health"
}

dependencies {
    implementation(project(":core-domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.health.connect)
}
