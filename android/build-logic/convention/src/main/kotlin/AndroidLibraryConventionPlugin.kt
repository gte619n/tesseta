import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Base convention for every Android library module: applies the Android library
 * + Kotlin plugins and the compileSdk/minSdk/Java-21/jvmToolchain config that was
 * previously copy-pasted into all ~12 modules' `android {}` blocks. Modules keep
 * only their `namespace` and their own dependencies.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")

        extensions.configure<LibraryExtension> {
            compileSdk = 35
            defaultConfig {
                minSdk = 29
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
        extensions.configure<KotlinAndroidProjectExtension> {
            jvmToolchain(21)
        }
    }
}
