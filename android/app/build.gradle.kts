plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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

        // IMPL-12: backend base URL for the Retrofit client. Defaults to the
        // deployed Cloud Run service so debug builds work on real devices and
        // emulators without a local backend. Override for local dev with
        // -PbackendBaseUrl=http://10.0.2.2:8080 (emulator → host localhost) or
        // the BACKEND_BASE_URL env var.
        val backendBaseUrl =
            (project.findProperty("backendBaseUrl") as String?)
                ?: System.getenv("BACKEND_BASE_URL")
                ?: "https://health-fitness-backend-mbysudfbja-uc.a.run.app"
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
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
    implementation(project(":core-ui"))
    implementation(project(":core-health"))
    implementation(project(":feature-workouts"))
    implementation(project(":feature-medical"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-goals"))
    implementation(project(":feature-nutrition"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.window.manager)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

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
