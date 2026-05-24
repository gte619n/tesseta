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
}
