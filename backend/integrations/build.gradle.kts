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

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
