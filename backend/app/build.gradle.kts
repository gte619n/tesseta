plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":persistence"))
    implementation(project(":integrations"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    // IMPL-20 Phase 2: Caffeine-backed @Cacheable layer for low-churn
    // reference reads (drug catalog, users) and per-user health snapshots.
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.caffeine)
    implementation(libs.spring.boot.starter.security)
    // IMPL-02: backend is a JWT resource server validating Google ID tokens.
    // The login flow runs on each client; backend never initiates OAuth.
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    // See persistence/build.gradle.kts — same JUnit Platform alignment.
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
