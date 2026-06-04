plugins {
    id("healthfitness.android.library")
}

android {
    namespace = "com.gte619n.healthfitness.domain"
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
