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
    ":core-ui",
    ":core-health",
    ":core-chat",
    ":feature-workouts",
    ":feature-medical",
    ":feature-chat",
    ":feature-goals",
    ":feature-nutrition",
    ":feature-settings",
    ":feature-blood",
    ":feature-body-composition",
)
