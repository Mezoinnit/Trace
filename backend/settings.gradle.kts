pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "trace-backend"

include(
    ":core:domain",
    ":core:application",
    ":infrastructure:security",
    ":infrastructure:persistence",
    ":server:api",
    ":server:worker",
    ":server:scheduler",
    ":test-support",
)