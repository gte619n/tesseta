plugins {
    `java-library`
}

tasks.test {
    // Pass through GEMINI_API_KEY for integration tests
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
}

dependencies {
    api(project(":core"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.autoconfigure)
    // Google Health REST + OAuth2 token exchange use java.net.http
    // (JDK built-in). KMS is the only external GCP client we pull in
    // through this module.
    implementation(libs.google.cloud.kms)
    implementation(libs.google.cloud.storage)
    implementation(libs.google.genai)
    implementation(libs.google.auth.library)
    // IMPL-AND-20 Phase 2: FCM fan-out transport (data-only silent push) via the
    // Firebase Admin SDK. `api` so the FirebaseMessaging bean the `app` module
    // provides is on the compile classpath there too.
    api(libs.firebase.admin)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
