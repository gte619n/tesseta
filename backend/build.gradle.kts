import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    java
}

allprojects {
    group = "com.gte619n.healthfitness"
    version = "0.0.1-SNAPSHOT"
}

val springBootVersion: String = libs.versions.springBoot.get()

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        }
    }

    // Gradle 8.x ships with its own junit-platform-launcher (1.10.x) which
    // doesn't expose OutputDirectoryProvider — required by junit-platform
    // 1.11+ engines pulled in via Spring Boot's BOM. Declaring launcher as
    // testRuntimeOnly lets the Spring Boot BOM align it with the engine,
    // fixing "OutputDirectoryProvider not available" during test discovery.
    dependencies {
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
