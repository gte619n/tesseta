plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gte619n.healthfitness.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gte619n.healthfitness"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // IMPL-02: same checked-in keystore as phone, so wear-side Credential Manager
    // / Google identity checks use the SHA-1 we registered with Google's OAuth.
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
        }
    }

    // IMPL-AND-00: same flavor scaffolding as phone. Wear doesn't make
    // network calls yet (no core-network dependency), but BACKEND_BASE_URL
    // is wired into BuildConfig so the wear-side network module can drop in
    // later (IMPL-AND-08) without a Gradle re-shuffle.
    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "BACKEND_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".staging"
            val url = (project.findProperty("BACKEND_URL_STAGING") as String?)
                ?: "https://hf-backend-staging-XXXX.us-central1.run.app/"
            buildConfigField("String", "BACKEND_BASE_URL", "\"$url\"")
        }
        create("prod") {
            dimension = "env"
            val url = (project.findProperty("BACKEND_URL_PROD") as String?)
                ?: "https://hf-backend-XXXX.us-central1.run.app/"
            buildConfigField("String", "BACKEND_BASE_URL", "\"$url\"")
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
    implementation(project(":core-health"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.health.services.client)
    // Tiles + complications wired through but inert; surfaces added in IMPL-XX.
    implementation(libs.wear.tiles)
    implementation(libs.wear.complications)

    // IMPL-AND-00: wear-side Hilt graph. Separate from phone's — no shared
    // singletons. Tokens cross the gap via the Wearable Data Layer.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // IMPL-02: phone-to-wear token relay over the Wearable Data Layer plus a
    // DataStore-backed cache mirroring the phone-side IdTokenCache.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.datastore.preferences)
}
