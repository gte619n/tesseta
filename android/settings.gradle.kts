pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "health-fitness-android"

include(
    ":app",
    ":wear",
    ":core-data",
    ":core-domain",
    ":core-network",
    ":core-ui",
    ":core-health",
    ":feature-workouts",
    ":feature-medical",
    ":feature-chat",
)
