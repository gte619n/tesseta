plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gte619n.healthfitness.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gte619n.healthfitness"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // IMPL-02: Google sign-in via Credential Manager uses the WEB OAuth
        // client ID as the audience, even on Android. Override at build time
        // via -PwebOauthClientId=... or the WEB_OAUTH_CLIENT_ID env var; falls
        // back to empty so debug builds compile before secrets are wired.
        val webOauthClientId =
            (project.findProperty("webOauthClientId") as String?)
                ?: System.getenv("WEB_OAUTH_CLIENT_ID")
                ?: ""
        buildConfigField("String", "WEB_OAUTH_CLIENT_ID", "\"$webOauthClientId\"")
    }

    // IMPL-02: pin debug signing to the checked-in keystore at android/debug.keystore
    // so every developer's debug APK has the SHA-1 we registered with Google's
    // OAuth client. Without this, AGP defaults to ~/.android/debug.keystore and
    // Credential Manager will reject the sign-in with DEVELOPER_ERROR.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release signing is driven by env vars injected by Cloud Build via GCP Secret Manager.
        // All four vars must be present for the config to be active; local builds that lack them
        // produce an unsigned APK (same behaviour as before this block existed).
        create("release") {
            val keystorePath = System.getenv("ANDROID_RELEASE_KEYSTORE")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
            }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseKeystorePath = System.getenv("ANDROID_RELEASE_KEYSTORE")
            if (!releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // IMPL-AND-00: BACKEND_BASE_URL is a compile-time constant per flavor.
    // - dev   → http://10.0.2.2:8080/ (Android emulator → host loopback).
    // - staging → Cloud Run staging deployment.
    // - prod    → Cloud Run prod deployment.
    // Switching environments is a "change build variant in Android Studio"
    // action, not a runtime toggle. Real-device dev sessions use `staging`.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "BACKEND_BASE_URL", "\"http://10.0.2.2:8080/\"")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_dev"
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            val url = (project.findProperty("BACKEND_URL_STAGING") as String?)
                ?: "https://hf-backend-staging-XXXX.us-central1.run.app/"
            buildConfigField("String", "BACKEND_BASE_URL", "\"$url\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_release"
        }
        create("prod") {
            dimension = "env"
            val url = (project.findProperty("BACKEND_URL_PROD") as String?)
                ?: "https://hf-backend-XXXX.us-central1.run.app/"
            buildConfigField("String", "BACKEND_BASE_URL", "\"$url\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            manifestPlaceholders["networkSecurityConfig"] = "@xml/network_security_config_release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-network"))
    implementation(project(":core-ui"))
    implementation(project(":core-health"))
    implementation(project(":feature-workouts"))
    implementation(project(":feature-medical"))
    implementation(project(":feature-chat"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.window.manager)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)

    // IMPL-02: phone publishes ID tokens to paired wear nodes.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
