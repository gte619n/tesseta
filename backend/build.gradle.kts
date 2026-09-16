import java.util.concurrent.atomic.AtomicLong
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult

plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.gte619n.healthfitness"
version = "0.0.2-SNAPSHOT"

// Override two BOM-managed transitive versions whose CVE fixes land ahead of the
// Spring Boot 3.5.x line (the image scan gate blocks HIGH/CRITICAL with a fix
// available). Spring Boot's dependency management reads these `ext` properties.
//   jackson 2.21.4 — CVE-2026-54512 (databind RCE); Boot 3.5.x ships 2.19.
//   netty 4.1.138.Final — CVE-2026-42583 / -33870 / -44249 (codec) + CVE-2026-59901
//     / -55831 / -55833 / -56745 (codec-http DoS); Boot 3.5.x ships 4.1.12x.
//     Bumped 4.1.136 -> 4.1.137 for CVE-2026-75595 (CRITICAL, netty-handler),
//     then 4.1.137 -> 4.1.138 (SUP-002): 4.1.138.Final (released 2026-09-09)
//     rolls up ~23 further security/bug fixes and 4.1.137 is now superseded, so
//     the Trivy image-scan gate flags it as fixable — take the current release.
//   tomcat 10.1.59 — CVE-2026-65182 / -65905 / -68525 (CRITICAL: security
//     constraint bypass, auth bypass, unauthorized access); fixed upstream in
//     10.1.58 but that tag was never published to Maven Central, so we take the
//     next available 10.1.59. Boot 3.5.14 ships 10.1.54. (Previously 10.1.55
//     for CVE-2026-41293, also CRITICAL.)
//   spring-framework 6.2.19 — CVE-2026-41850 (SpEL DoS), CVE-2026-41842 (static
//     resource DoS), CVE-2026-41845 (webmvc XSS); Boot 3.5.x ships 6.2.18.
//   micrometer 1.15.12 — CVE-2026-40983 (gRPC DoS), CVE-2026-40984 (HTTP DoS);
//     Boot 3.5.x ships 1.15.11. Patch bump on the same minor line.
//   httpcore5 5.4.3 — CVE-2026-54399 (httpcore5) + CVE-2026-54428 (httpcore5-h2),
//     both HIGH; Boot 3.5.x pins 5.3.6. The property versions both core5 artifacts.
extra["jackson-bom.version"] = "2.21.4"
extra["netty.version"] = "4.1.138.Final"
extra["tomcat.version"] = "10.1.59"
extra["spring-framework.version"] = "6.2.19"
extra["micrometer.version"] = "1.15.12"
extra["httpcore5.version"] = "5.4.3"

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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

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
    // A bare Test task has no sources of its own. Point it at the `test` source
    // set's compiled output and runtime classpath — the emulator-tagged classes
    // live alongside the unit tests, split only by JUnit tag. Without this the
    // task resolves to NO-SOURCE and silently skips (locally AND in CI), which
    // is exactly how the 8 firestore-emulator classes went unrun (baseline DL-6).
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("firestore-emulator")
    }
    shouldRunAfter(tasks.test)
    environment("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY") ?: "")
    val emulatorRequired =
        System.getProperty("firestore.emulator.required", System.getenv("CI") ?: "false")
    systemProperty("firestore.emulator.required", emulatorRequired)
    // Fail loudly if the wiring regresses and the suite runs zero tests while
    // the emulator is required (CI). A skip must never masquerade as a pass again.
    if (emulatorRequired == "true") {
        val executed = AtomicLong(0)
        afterSuite(
            KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
                if (desc.parent == null) executed.set(result.testCount)
            })
        )
        doLast {
            if (executed.get() == 0L) {
                throw GradleException(
                    "integrationTest ran 0 tests but firestore.emulator.required=true — " +
                        "the emulator suite is not wired (see DL-6). Refusing to pass."
                )
            }
        }
    }
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
