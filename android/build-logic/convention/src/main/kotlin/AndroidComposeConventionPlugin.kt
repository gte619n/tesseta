import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * Compose convention: applies the Compose compiler plugin, turns on the Compose
 * build feature, and pulls in the standard Compose UI dependency block that every
 * Compose module repeated verbatim. Apply AFTER `healthfitness.android.library`
 * (it configures the already-applied LibraryExtension).
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.getByType<LibraryExtension>().buildFeatures.compose = true

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            add("implementation", platform(libs.findLibrary("compose-bom").get()))
            add("implementation", libs.findLibrary("compose-material3").get())
            add("implementation", libs.findLibrary("compose-ui").get())
            add("implementation", libs.findLibrary("compose-ui-graphics").get())
            add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
            add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
            add("implementation", libs.findLibrary("compose-material-icons-extended").get())
        }
    }
}
