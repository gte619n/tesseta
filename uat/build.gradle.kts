// Standalone UAT (Selenium) harness. Not part of the backend/android Gradle
// builds — it drives the running web app + backend over HTTP, so it has no
// source dependency on them. Run via infra/scripts/uat.sh, which boots the
// emulator + backend (dev-login enabled, AI stubbed) + web (UAT_AUTH_ENABLED)
// first, then `./gradlew test` here.
//
// Selenium 4.6+ ships Selenium Manager, which auto-resolves a matching
// chromedriver — no WebDriverManager / manual driver needed.
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.seleniumhq.selenium:selenium-java:4.27.0")
    // UatBackendClient: dev-login + REST seeding/reset, shared (in contract)
    // with future Android instrumented tests.
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // The system-under-test is an external running stack Gradle can't fingerprint,
    // so never treat a prior pass as up-to-date — always exercise the live app.
    outputs.upToDateWhen { false }
    // Base URLs are overridable so CI / a non-default port (see uat.sh) work.
    systemProperty("uat.webBaseUrl", System.getProperty("uat.webBaseUrl") ?: "http://localhost:3000")
    systemProperty("uat.backendBaseUrl", System.getProperty("uat.backendBaseUrl") ?: "http://localhost:8080")
    systemProperty("uat.firestoreEmulatorHost", System.getProperty("uat.firestoreEmulatorHost") ?: "127.0.0.1:8081")
    systemProperty("uat.firestoreProjectId", System.getProperty("uat.firestoreProjectId") ?: "demo-uat")
    systemProperty("uat.headless", System.getProperty("uat.headless") ?: "true")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
