plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.gte619n.healthfitness"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    // -parameters keeps constructor/param names at runtime, which Spring uses
    // for @RequestParam/@PathVariable binding without explicit value = "...".
    options.compilerArgs.add("-parameters")
}

dependencies {
    // Web + validation + actuator: the HTTP surface and health/probe endpoints.
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // IMPL-20 Phase 2: Caffeine-backed @Cacheable layer for low-churn reference
    // reads (drug catalog, users) and per-user health snapshots.
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.caffeine)

    // IMPL-02: backend is a JWT resource server validating Google ID tokens.
    // The login flow runs on each client; backend never initiates OAuth.
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    // Persistence: Cloud Firestore is the source of truth (no JPA/SQL).
    implementation(libs.google.cloud.firestore)

    // Integrations: Gemini (genai), GCS, KMS envelope encryption, Google auth,
    // and FCM fan-out (Firebase Admin) for the offline-first sync stack.
    implementation(libs.google.genai)
    implementation(libs.google.cloud.storage)
    implementation(libs.google.cloud.kms)
    implementation(libs.google.auth.library)
    implementation(libs.firebase.admin)

    // Verifies the ECDSA-P256 signature Google Health stamps on every webhook
    // (X-HEALTHAPI-SIGNATURE), using Google's published Tink keyset.
    implementation(libs.google.tink)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    // Gradle's embedded test worker ships an older junit-platform-launcher than
    // the engine pulled in via Spring Boot's BOM; pin it so the versions align.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass.set("com.gte619n.healthfitness.HealthFitnessApplication")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
    // IMPL-20 Phase 3: layered jar so Cloud Run can cache the rarely-changing
    // dependency layers across deploys and only ship the application layer,
    // cutting image build + cold-start time.
    layered {
        enabled.set(true)
    }
}

tasks.test {
    useJUnitPlatform()
    // Pass GEMINI_API_KEY through to the live-Gemini preview harness
    // (WorkoutSeedEnrichmentPreviewTest); empty when unset so other tests
    // are unaffected.
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
}
