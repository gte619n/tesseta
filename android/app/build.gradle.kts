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
        // client ID as the audience, even on Android. Every build MUST embed a
        // real client ID — sign-in (and therefore the whole app) is useless
        // without one, so resolution that comes up empty FAILS the build rather
        // than shipping a broken APK. See resolveWebOauthClientId() below for the
        // lookup order (property -> env var -> GCP Secret Manager).
        val webOauthClientId = resolveWebOauthClientId(providers)
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
    implementation(project(":feature-settings"))
    implementation(project(":feature-blood"))
    implementation(project(":feature-body-composition"))

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

/**
 * Resolves the WEB OAuth client ID baked into BuildConfig.WEB_OAUTH_CLIENT_ID.
 *
 * Google sign-in via Credential Manager passes this as the server client ID
 * (audience). Without a real value the app cannot authenticate, so this build
 * REFUSES to produce an APK without one. Resolution order:
 *   1. -PwebOauthClientId=<id>            explicit Gradle property override
 *   2. WEB_OAUTH_CLIENT_ID env var        used by Cloud Build (android/cloudbuild.yaml)
 *   3. gcloud Secret Manager              oauth-web-client-id in the GCP project
 *                                         (override project with -PgcpProjectId=...)
 *
 * If none yields a value that looks like a real client ID the build fails with
 * actionable instructions. With Gradle's configuration cache enabled the gcloud
 * lookup only runs when the cache is (re)computed, not on every build.
 */
fun resolveWebOauthClientId(providers: ProviderFactory): String {
    val gcpProject =
        providers.gradleProperty("gcpProjectId").orNull?.takeIf { it.isNotBlank() }
            ?: "health-fitness-160"
    val secretName = "oauth-web-client-id"

    fun looksLikeClientId(value: String?): Boolean =
        !value.isNullOrBlank() && value.trim().endsWith(".apps.googleusercontent.com")

    // 1. Explicit Gradle property.
    providers.gradleProperty("webOauthClientId").orNull?.trim()?.let {
        if (looksLikeClientId(it)) return it
    }

    // 2. Environment variable (Cloud Build injects this from Secret Manager).
    providers.environmentVariable("WEB_OAUTH_CLIENT_ID").orNull?.trim()?.let {
        if (looksLikeClientId(it)) return it
    }

    // 3. Fetch straight from Secret Manager via gcloud (local dev convenience).
    val fromSecretManager: String? =
        try {
            val output =
                providers.exec {
                    isIgnoreExitValue = true
                    commandLine(
                        "gcloud", "secrets", "versions", "access", "latest",
                        "--secret=$secretName", "--project=$gcpProject",
                    )
                }
            if (output.result.orNull?.exitValue == 0) {
                output.standardOutput.asText.orNull?.trim()
            } else {
                null
            }
        } catch (_: Exception) {
            // gcloud missing / not on PATH — treated as "unresolved" below.
            null
        }
    if (looksLikeClientId(fromSecretManager)) return fromSecretManager!!

    throw GradleException(
        """
        |Could not resolve a real WEB OAuth client ID — refusing to build a broken APK.
        |Google sign-in (and therefore the entire app) requires BuildConfig.WEB_OAUTH_CLIENT_ID.
        |
        |Fix ONE of the following:
        |  • Pass it explicitly:
        |      ./gradlew :app:assembleDebug -PwebOauthClientId=<id>.apps.googleusercontent.com
        |  • Export it in the environment:
        |      export WEB_OAUTH_CLIENT_ID=<id>.apps.googleusercontent.com
        |  • Let Gradle fetch it from Secret Manager (default) — authenticate gcloud first:
        |      gcloud auth login
        |      gcloud config set project $gcpProject
        |
        |Attempted secret '$secretName' in GCP project '$gcpProject' but got no usable value.
        """.trimMargin(),
    )
}
