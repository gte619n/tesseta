plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.gte619n.healthfitness"
version = "0.0.1-SNAPSHOT"

// Override two BOM-managed transitive versions whose CVE fixes land ahead of the
// Spring Boot 3.5.x line (the image scan gate blocks HIGH/CRITICAL with a fix
// available). Spring Boot's dependency management reads these `ext` properties.
//   jackson 2.21.4 — CVE-2026-54512 (databind RCE); Boot 3.5.x ships 2.19.
//   netty 4.1.136.Final — CVE-2026-42583 / -33870 / -44249 (codec) + CVE-2026-59901
//     / -55831 / -55833 / -56745 (codec-http DoS); Boot 3.5.x ships 4.1.12x.
//   tomcat 10.1.55 — CVE-2026-41293 (CRITICAL); Boot 3.5.14 ships 10.1.54.
//   spring-framework 6.2.19 — CVE-2026-41850 (SpEL DoS), CVE-2026-41842 (static
//     resource DoS), CVE-2026-41845 (webmvc XSS); Boot 3.5.x ships 6.2.18.
//   micrometer 1.15.12 — CVE-2026-40983 (gRPC DoS), CVE-2026-40984 (HTTP DoS);
//     Boot 3.5.x ships 1.15.11. Patch bump on the same minor line.
extra["jackson-bom.version"] = "2.21.4"
extra["netty.version"] = "4.1.136.Final"
extra["tomcat.version"] = "10.1.55"
extra["spring-framework.version"] = "6.2.19"
extra["micrometer.version"] = "1.15.12"

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

    // ADR-0020 (decision D16): live OpenAPI 3 spec + Swagger UI for the /v1
    // third-party API. Scoped to /v1 via springdoc.paths-to-match so it never
    // documents the first-party /api surface.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

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

// The unit/slice suite. Excludes the emulator-backed integration tests (tag
// "firestore-emulator") so it stays fast and needs no external processes.
tasks.test {
    useJUnitPlatform {
        excludeTags("firestore-emulator")
    }
    // Pass GEMINI_API_KEY through to the live-Gemini preview harness
    // (WorkoutSeedEnrichmentPreviewTest); empty when unset so other tests
    // are unaffected.
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
    // Forward the opt-in regen flag to the forked test JVM so
    // `-Dopenapi.update=true` reaches V1OpenApiSnapshotTest (ADR-0020, D16).
    systemProperty("openapi.update", System.getProperty("openapi.update", "false"))
}

// Emulator-backed integration tests (Firestore repositories, transactions,
// query scoping). Tagged "firestore-emulator"; FirestoreEmulatorExtension
// boots a `firebase emulators` Firestore instance per JVM on an ephemeral
// port (no Docker). CI sets `firestore.emulator.required=true` so a missing
// firebase CLI fails the build instead of silently skipping.
val integrationTest by tasks.registering(Test::class) {
    description = "Runs Firestore-emulator integration tests."
    group = "verification"
    useJUnitPlatform {
        includeTags("firestore-emulator")
    }
    shouldRunAfter(tasks.test)
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
    systemProperty(
        "firestore.emulator.required",
        System.getProperty("firestore.emulator.required", System.getenv("CI") ?: "false")
    )
}

tasks.named("check") {
    dependsOn(integrationTest)
}

jacoco {
    toolVersion = "0.8.12"
}

// Aggregate coverage from both the unit suite and the emulator integration
// suite into one XML (for CI ratchet) + HTML (for humans) report.
tasks.jacocoTestReport {
    executionData(tasks.test.get(), integrationTest.get())
    dependsOn(tasks.test, integrationTest)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
