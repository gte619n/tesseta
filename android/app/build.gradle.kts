plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // IMPL-AND-20 (Phase 6): processes app/google-services.json and wires
    // FirebaseApp auto-init for FCM. Applied last so it sees the android block.
    alias(libs.plugins.google.services)
}

// Base marketing version — the single source of truth for the "0.x.y" prefix.
// CI appends the build number to this (see resolveVersionName); bump it for a
// real release. infra/scripts compose release notes against this same string.
val baseVersionName = "0.1.0"

android {
    namespace = "com.gte619n.healthfitness.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gte619n.healthfitness"
        minSdk = 29
        targetSdk = 35

        // Versioning. The base marketing version lives here as the single source
        // of truth; CI (android/cloudbuild.yaml) injects a monotonic build number
        // so every Firebase App Distribution release shows a distinct version
        // instead of a perpetual "(1) / 0.1.0". Resolution order for each value:
        //   1. -PandroidVersionCode / -PandroidVersionName Gradle property
        //   2. ANDROID_VERSION_CODE / ANDROID_VERSION_NAME env var (Cloud Build)
        //   3. local default (versionCode 1, base versionName) — unchanged local UX
        // See resolveVersionCode()/resolveVersionName() below.
        val resolvedVersionCode = resolveVersionCode(providers)
        versionCode = resolvedVersionCode
        versionName = resolveVersionName(providers, baseVersionName, resolvedVersionCode)
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

        // ABI filtering: a real phone only needs ARM. The x86/x86_64 libs add
        // ~40 MB of dead weight to the (universal) debug APK and are only useful
        // for Intel-host emulators. Default to ARM-only; pass -PincludeX86 to add
        // the x86 ABIs when you're specifically building for such an emulator.
        // (Apple-Silicon emulators run arm64, so they don't need this either.)
        ndk {
            abiFilters.clear()
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            if (providers.gradleProperty("includeX86").isPresent) {
                abiFilters += listOf("x86", "x86_64")
            }
        }
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
    implementation(project(":feature-goals"))
    implementation(project(":feature-nutrition"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-blood"))
    implementation(project(":feature-body-composition"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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

    // IMPL-AND-20 (Phase 4): WorkManager + Hilt worker factory. :app hosts the
    // HiltWorkerFactory and disables WorkManager's default initializer so the
    // sync workers (defined in :core-data) get their dependencies injected.
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // IMPL-AND-20 (Phase 6): Firebase Cloud Messaging — the silent data-message
    // client. The BoM aligns the messaging artifact; the google-services plugin
    // (above) auto-initializes FirebaseApp from app/google-services.json.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)

    // IMPL-02: phone publishes ID tokens to paired wear nodes.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    // IMPL-AND-20 (Phase 6): JVM unit tests for FirstSyncGate + TokenRegistration.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.retrofit)
    testImplementation(libs.retrofit.moshi)
    testImplementation(libs.moshi)
    testImplementation(libs.moshi.kotlin)
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

/**
 * Resolves the integer versionCode baked into the APK.
 *
 * Firebase App Distribution shows this as the build "version" — a constant 1
 * makes every release look identical. CI injects a monotonically increasing
 * value (commit count; see android/cloudbuild.yaml) so releases are
 * distinguishable and ordered. Resolution order:
 *   1. -PandroidVersionCode=<int>   explicit Gradle property override
 *   2. ANDROID_VERSION_CODE env var used by Cloud Build
 *   3. 1                            local default (unchanged local behaviour)
 */
fun resolveVersionCode(providers: ProviderFactory): Int {
    providers.gradleProperty("androidVersionCode").orNull?.trim()?.toIntOrNull()?.let {
        if (it > 0) return it
    }
    providers.environmentVariable("ANDROID_VERSION_CODE").orNull?.trim()?.toIntOrNull()?.let {
        if (it > 0) return it
    }
    return 1
}

/**
 * Resolves the human-readable versionName shown as the "display version" in
 * Firebase App Distribution. A constant "0.1.0" gives no signal about which
 * build a tester is running; CI derives "<base> (<buildNumber>)" so the text
 * version tracks the build. Resolution order:
 *   1. -PandroidVersionName=<str>   explicit Gradle property override
 *   2. ANDROID_VERSION_NAME env var used by Cloud Build
 *   3. derived: "<base> (<versionCode>)" when CI supplied a real build number,
 *      otherwise just "<base>" for local builds.
 */
fun resolveVersionName(providers: ProviderFactory, base: String, versionCode: Int): String {
    providers.gradleProperty("androidVersionName").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return it
    }
    providers.environmentVariable("ANDROID_VERSION_NAME").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return it
    }
    return if (versionCode > 1) "$base ($versionCode)" else base
}
