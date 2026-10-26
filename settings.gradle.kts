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
rootProject.name = "frostline-tv"
include(
    ":app",
    ":core:ui",
    ":core:data",
    ":core:twitch",
    ":core:twitch-internal",
    ":feature:home",
    ":feature:auth",
    ":feature:player",
    ":feature:chat",
    ":feature:channel",
    ":baselineprofile",
)
